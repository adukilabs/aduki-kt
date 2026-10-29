package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.AccountSession
import java.time.Instant

/**
 * Id is the Aduki ID client (ADK-KT-003 K2): one sign-in, many audiences.
 *
 * It keeps the session, the current refresh token and one access token per
 * audience (`mail`, `id`, later `space`). Refresh tokens rotate and a spent
 * one presented again revokes the session at Aduki ID, so every renewal is
 * serialized and a refresh token is dropped the moment it is known to be
 * spent; it is never sent twice.
 *
 * [base] is the Aduki ID base, e.g. `https://id.aduki.pro/v1`. [client] must
 * carry no credentials of its own.
 */
class Id(
    private val client: OkHttpClient,
    private val base: String = Endpoints.ID,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Cached(val token: String, val until: Long, val expires: String)

    private val lock = Any()
    private val cache = HashMap<String, Cached>()
    private var refresh = ""
    private var session = ""

    /** The session hex of the current sign-in, or blank. */
    fun session(): String = synchronized(lock) { session }

    /** True while a refresh token is held. */
    fun signedIn(): Boolean = synchronized(lock) { refresh.isNotBlank() }

    /** Signs in and caches the access token for [audience]. */
    fun signIn(
        handle: String,
        password: String,
        code: String? = null,
        backup: String? = null,
        audience: String = Endpoints.AUDIENCE
    ): Tokens {
        val tokens = Login.submit(client, base, handle, password, code, backup, audience)
        synchronized(lock) {
            cache.clear()
            session = tokens.session
            refresh = tokens.refresh
            store(audience, tokens)
        }
        return tokens
    }

    /** Adopts an existing sign-in, e.g. one restored from storage. */
    fun adopt(tokens: Tokens, audience: String = Endpoints.AUDIENCE) = synchronized(lock) {
        cache.clear()
        session = tokens.session
        refresh = tokens.refresh
        if (tokens.token.isNotBlank()) store(audience, tokens)
    }

    /** The refresh token to persist after each renewal (blank once spent). */
    fun refreshToken(): String = synchronized(lock) { refresh }

    /** A valid access token for [audience], renewed when missing or expired. */
    fun token(audience: String = Endpoints.AUDIENCE): String = synchronized(lock) {
        val hit = cache[audience]
        if (hit != null && hit.until > clock()) hit.token else renew(audience, null)
    }

    /**
     * Renews the [audience] token now, unless another caller already replaced
     * [stale] with a live one. Returns the current pair: access token,
     * rotated refresh token, lifetime and session.
     */
    fun rotate(audience: String = Endpoints.AUDIENCE, stale: String? = null): Tokens = synchronized(lock) {
        val token = renew(audience, stale)
        Tokens(token, refresh, cache[audience]?.expires.orEmpty(), session)
    }

    /**
     * Sends the request [build] makes with the [audience] token. On a 401 the
     * token is renewed once (unless another caller already did) and the
     * request is sent once more; a second 401 is returned as is, never looped.
     */
    fun call(audience: String, build: (token: String) -> Request): Response {
        val sent = token(audience)
        val first = client.newCall(build(sent)).execute()
        if (first.code != 401) return first
        first.close()
        val fresh = synchronized(lock) { renew(audience, sent) }
        return client.newCall(build(fresh)).execute()
    }

    /**
     * Signs out: revokes the session at Aduki ID and forgets every token.
     * When revocation fails the (rotated) refresh token is kept for a retry.
     */
    fun signOut(): Boolean = synchronized(lock) {
        if (session.isBlank()) {
            clear()
            return false
        }
        val outcome = Login.logout(client, base, session, refresh)
        if (outcome.revoked || outcome.refresh.isBlank()) {
            clear()
        } else {
            cache.clear()
            refresh = outcome.refresh
        }
        outcome.revoked
    }

    /** Forgets everything locally without calling Aduki ID. */
    fun clear() = synchronized(lock) {
        cache.clear()
        refresh = ""
        session = ""
    }

    // Must hold [lock]. [stale] is the token the caller was refused with: if
    // the cache already holds a different live one, another caller renewed.
    private fun renew(audience: String, stale: String?): String {
        val hit = cache[audience]
        if (stale != null && hit != null && hit.token != stale && hit.until > clock()) return hit.token
        if (refresh.isBlank()) {
            throw AdukiException.Unauthorized("Signed out: no refresh token")
        }
        val spending = refresh
        try {
            val fresh = Login.refresh(client, base, spending, audience)
            refresh = fresh.refresh
            store(audience, fresh)
            return fresh.token
        } catch (e: AdukiException.Unauthorized) {
            refresh = "" // spent, expired or revoked: never present it again
            cache.clear()
            throw e
        } catch (e: AdukiException.Auth) {
            // 2xx that failed validation: the old token is spent already.
            refresh = e.refresh
            cache.clear()
            throw e
        }
        // Network failures and 429/5xx leave the token unspent: keep it.
    }

    private fun store(audience: String, tokens: Tokens) {
        val seconds = tokens.expires.toLongOrNull() ?: 0L
        // Renew 30 s early so a token is not refused in flight.
        cache[audience] = Cached(tokens.token, clock() + (seconds - 30).coerceAtLeast(0) * 1000, tokens.expires)
    }

    /** Discovers personal signup requirements from Aduki ID. */
    fun requirements(): Requirements = Signup.requirements(client, base)

    /** Stage 1: Initiates personal account registration. */
    fun create(handle: String, name: String, password: String, phone: String? = null): Enrolment =
        Signup.create(client, base, handle, name, password, phone)

    /** Stage 2: Confirms the authenticator code and returns backup codes. */
    fun verify(hex: String, password: String, code: String): List<String> =
        Signup.verify(client, base, hex, password, code)

    /** Lists active sessions for the authenticated account from Aduki ID. */
    fun sessions(): List<AccountSession> {
        val response = call("id") { token ->
            Request.Builder()
                .url(url("sessions"))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        return response.use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 401) throw AdukiException.Unauthorized("Unauthorized")
                throw AdukiException.Api(resp.code, "sessions.error", "Failed to list sessions: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<AccountSession>()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                list.add(
                    AccountSession(
                        id = item.getString("session"),
                        device = if (item.isNull("device")) null else item.optString("device").ifBlank { null },
                        tier = item.optInt("tier", 2),
                        bound = item.optBoolean("bound", false),
                        created = item.optString("created").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        seen = item.optString("seen").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        current = item.optBoolean("current", false)
                    )
                )
            }
            list
        }
    }

    /** Revokes the session with [hex]. Returns true if successfully revoked. */
    fun revoke(hex: String): Boolean {
        require(hex.isNotBlank()) { "Session hex cannot be blank" }
        val response = call("id") { token ->
            Request.Builder()
                .url(url("sessions/$hex"))
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
        }
        return response.use { resp ->
            resp.isSuccessful || resp.code == 404
        }
    }

    private fun url(path: String) = "${base.trimEnd('/')}/$path"
}
