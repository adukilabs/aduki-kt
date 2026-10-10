package pro.aduki.net.http

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import pro.aduki.crypto.dpop.Key
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Dpop signs DPoP proofs (RFC 9449; ADK-KT-003 K5, ADK-AUTH-001 §9) with a
 * device [key] and is an OkHttp application interceptor that applies them.
 *
 * - A request with no `Authorization` (sign-in, refresh) gets a proof without
 *   `ath`; Aduki ID then binds the session to the key.
 * - A request whose token is bound to this key (its `cnf.jkt` equals the
 *   key's thumbprint) is sent as `Authorization: DPoP <token>` with a proof
 *   carrying `ath`.
 * - Any other credential (API key, unbound token) is left untouched.
 * - A `DPoP-Nonce` from the server is remembered per host; a 401 or 400
 *   carrying a fresh nonce is retried once with it.
 *
 * Every proof has a new random `jti` and the current `iat`; proofs are single
 * use at the server. The private key is never read: [Key] only signs.
 */
class Dpop(
    private val key: Key,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) : Interceptor {

    private val nonces = ConcurrentHashMap<String, String>()

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
    fun apply(request: Request, nonce: String? = nonces[request.url.host]): Request {
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
        val response = chain.proceed(apply(request))
        val nonce = response.header("DPoP-Nonce")
        if (nonce != null) nonces[request.url.host] = nonce
        if (nonce == null || (response.code != 401 && response.code != 400)) return response
        // Retry once, with the nonce the server just handed out.
        response.close()
        val retry = chain.proceed(apply(request, nonce))
        retry.header("DPoP-Nonce")?.let { nonces[request.url.host] = it }
        return retry
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
}
