package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import pro.aduki.crypto.dpop.Key
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Dpop signs DPoP proofs (RFC 9449; ADK-KT-003 K5, ADK-AUTH-001 §9) with a
 * device key and is an OkHttp application interceptor that applies them.
 *
 * - A request with no `Authorization` (sign-in, refresh) gets a proof without
 *   `ath`; Aduki ID then binds the session to the key.
 * - A request whose token is bound to this key (its `cnf.jkt` equals the
 *   key's thumbprint) is sent as `Authorization: DPoP <token>` with a proof
 *   carrying `ath`.
 * - Any other credential (API key, unbound token) is left untouched.
 * - The `DPoP-Nonce` of every answer is remembered per origin (shared by every
 *   [Dpop] of the process) and put in the next proofs, so a phone whose clock
 *   is wrong is not refused. An answer `401 auth.nonce` (or OAuth `400
 *   use_dpop_nonce`) is retried once with the fresh nonce; no other failure
 *   is retried here.
 *
 * The key is read when a proof is signed, not when the client is built
 * ([lazy]): a Keystore that fails once at start does not leave the process
 * without a key (and a bound session refused at every refresh).
 *
 * Every proof has a new random `jti` and the current `iat`; proofs are single
 * use at the server. The private key is never read: [Key] only signs.
 */
class Dpop private constructor(
    private val source: () -> Key,
    private val clock: () -> Long,
    private val random: SecureRandom
) : Interceptor {

    constructor(
        key: Key,
        clock: () -> Long = System::currentTimeMillis,
        random: SecureRandom = SecureRandom()
    ) : this({ key }, clock, random)

    @Volatile
    private var opened: Key? = null

    // The key, opened on first use and kept once it worked.
    private val key: Key
        get() = opened ?: source().also { opened = it }

    private val nonces get() = NONCES

    /** The key's thumbprint, as stored by Aduki ID in `cnf.jkt`. */
    fun thumbprint(): String = key.thumbprint()

    /**
     * One proof for [method] on [url] (query and fragment are not signed).
     * Pass the [access] token to bind the proof to it with `ath`, and the
     * server's [nonce] when it asked for one.
     */
    fun proof(method: String, url: String, access: String? = null, nonce: String? = null): String {
        val header = JSONObject()
            .put("typ", "dpop+jwt")
            .put("alg", key.alg)
            .put("jwk", JSONObject(key.jwk))
        val claims = JSONObject()
            .put("htm", method.uppercase())
            .put("htu", url.substringBefore('#').substringBefore('?'))
            .put("iat", clock() / 1000)
            .put("jti", jti())
        if (access != null) claims.put("ath", b64(MessageDigest.getInstance("SHA-256").digest(access.toByteArray())))
        if (nonce != null) claims.put("nonce", nonce)
        val input = "${b64(header.toString().toByteArray())}.${b64(claims.toString().toByteArray())}"
        return "$input.${b64(key.sign(input.toByteArray()))}"
    }

    /**
     * Returns [request] with its DPoP header and scheme applied by the rules
     * above (also usable to re-sign a request an authenticator rebuilt).
     */
    fun apply(request: Request, nonce: String? = nonces[origin(request)]): Request {
        val sent = request.header("Authorization")
        val token = sent?.substringAfter(' ', "")?.takeIf { sent.startsWith("Bearer ") || sent.startsWith("DPoP ") }
        val url = request.url.toString()
        return when {
            sent == null || sent.isBlank() ->
                request.newBuilder().header("DPoP", proof(request.method, url, null, nonce)).build()
            token != null && bound(token) ->
                request.newBuilder()
                    .header("Authorization", "DPoP $token")
                    .header("DPoP", proof(request.method, url, token, nonce))
                    .build()
            else -> request
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(signed(request))
        val nonce = response.header("DPoP-Nonce")?.ifBlank { null }
        if (nonce != null) nonces[origin(request)] = nonce
        if (response.code != 401 && response.code != 400) return response
        // Only a nonce challenge is retried: a refresh refused as reused or
        // revoked must not be sent twice.
        val body = runCatching { response.peekBody(PEEK).string() }.getOrDefault("")
        val challenge = response.headers("WWW-Authenticate").any { it.contains("use_dpop_nonce") }
        if (Failures.kind(body) !in Failures.NONCE && !challenge) return response
        val fresh = nonce ?: Failures.nonce(response, body) ?: return response
        nonces[origin(request)] = fresh
        response.close()
        val retry = chain.proceed(signed(request, fresh))
        retry.header("DPoP-Nonce")?.ifBlank { null }?.let { nonces[origin(request)] = it }
        return retry
    }

    /** The nonce last received from the origin of [url] (`host:port`), or null. */
    fun nonce(url: String): String? = url.toHttpUrlOrNull()?.let { nonces["${it.host}:${it.port}"] }

    // Nonces are kept per origin: one server, one nonce.
    private fun origin(request: Request): String = "${request.url.host}:${request.url.port}"

    // [apply] for the interceptor. A key that cannot sign (a Keystore key
    // invalidated or busy) throws a runtime or security exception; OkHttp
    // expects interceptors to fail with an IOException, which callers handle
    // (an asynchronous call would otherwise crash its dispatcher thread).
    private fun signed(request: Request, nonce: String? = nonces[origin(request)]): Request = try {
        apply(request, nonce)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("DPoP proof could not be signed (${e.javaClass.simpleName})", e)
    }

    // True when the access token (a JWT) is bound to this key.
    private fun bound(token: String): Boolean {
        val body = token.split('.').getOrNull(1) ?: return false
        return try {
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(body)))
            claims.optJSONObject("cnf")?.optString("jkt") == key.thumbprint()
        } catch (_: Exception) {
            false
        }
    }

    private fun jti(): String = ByteArray(16).also(random::nextBytes).let(::b64)

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        // Server nonces by origin (host:port), for every Dpop of the process (RFC 9449 §8:
        // a nonce belongs to the server, not to a key).
        private val NONCES = ConcurrentHashMap<String, String>()

        // How much of a 400/401 body is read to find its kind.
        private const val PEEK = 16_384L

        /**
         * A [Dpop] whose key is read from [source] when the first proof is
         * signed, and again on the next request while it fails (a Keystore
         * that is busy or not unlocked yet). A failure to read it fails that
         * request with an IOException: tokens are kept.
         */
        fun lazy(
            source: () -> Key,
            clock: () -> Long = System::currentTimeMillis,
            random: SecureRandom = SecureRandom()
        ): Dpop = Dpop(source, clock, random)

        /** Forgets every remembered nonce (tests). */
        fun forgetNonces() = NONCES.clear()
    }
}
