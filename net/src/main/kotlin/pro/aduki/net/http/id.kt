package pro.aduki.net.http

import okhttp3.RequestBody.Companion.toRequestBody

import okhttp3.MediaType.Companion.toMediaType

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.AccountProfile
import pro.aduki.core.models.AccountSession
import pro.aduki.core.models.CenterView
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
        audience: String = Endpoints.AUDIENCE,
        device: String? = null
    ): Tokens {
        val tokens = Login.submit(client, base, handle, password, code, backup, audience, device)
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
            // Spent, expired or revoked: never present it again. Any other
            // 401 (a second factor, a stale epoch) leaves the token unspent.
            if (e.ended) {
                refresh = ""
                cache.clear()
            }
            throw e
        } catch (e: AdukiException.Auth) {
            // 2xx that failed validation: the old token is spent already.
            refresh = e.refresh
            cache.clear()
            throw e
        }
        // A refused proof (AdukiException.Proof: auth.dpop, auth.nonce), network
        // failures and 429/5xx leave the token unspent: keep it.
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
        Signup.verify(client, base, hex, password, code).codes

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
                if (resp.code == 401) Failures.raise(resp, resp.body?.string().orEmpty(), "Sessions")
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

    /**
     * Asks Aduki ID for a single-use [Handoff] that lets the app [target] (a
     * package name) open its own session of this account without a password
     * or second factor: `POST /v1/handoffs`. [ttl] is in seconds, 30 to
     * 86400. With [jkt] (the RFC 7638 thumbprint of the **receiving** app's
     * DPoP key) the code works only with a proof by that key. A new handoff
     * for the same target replaces an unredeemed one; all of them end with
     * this session. [Handoff.expires] is on this device's clock (from the
     * server's `expires_in`).
     */
    fun handoff(target: String, ttl: Long = Handoff.TTL, jkt: String? = null): Handoff {
        require(target.isNotBlank()) { "target is required" }
        require(ttl in 30..86_400) { "ttl: 30 seconds to 24 hours" }
        val body = JSONObject().put("target", target.trim()).put("ttl", ttl)
            .apply { jkt?.trim()?.ifBlank { null }?.let { put("jkt", it) } }
            .toString()
        val response = call("id") { token ->
            Request.Builder()
                .url(url("handoffs"))
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build()
        }
        return response.use { resp ->
            val data = Login.data(resp, "Handoff")
            Handoff(Login.required(data, "handoff", "handoff"), Handoff.local(data, clock))
        }
    }

    /**
     * The signed-in account's profile, `GET /v1/me`: hex, handle, name, kind,
     * tenant, photo tag, center, unlock, and this session and its device group.
     */
    fun me(): AccountProfile {
        val response = call("id") { token ->
            Request.Builder().url(url("me")).header("Authorization", "Bearer $token").get().build()
        }
        return response.use { Center.profile(Login.data(it, "Profile")) }
    }

    /**
     * The Account Center of the signed-in account, `GET /v1/center`: an
     * account in no center is listed alone with [CenterView.center] null.
     */
    fun center(): CenterView {
        val response = call("id") { token ->
            Request.Builder().url(url("center")).header("Authorization", "Bearer $token").get().build()
        }
        return response.use { Center.view(Login.data(it, "Center")) }
    }

    /**
     * Adds the account a link [ticket] names to the signed-in account's Account
     * Center: `POST /v1/links {ticket}`. The ticket comes from a sign-in with
     * `link: true` in this app; this session must be bound to the same DPoP
     * key (the client carries it).
     *
     * @throws AdukiException.Network with code 403 when a tenant forbids
     * links, 409 when the center would hold two personal accounts.
     * @throws AdukiException.Unauthorized (`auth.invalid`) for an unknown,
     * used or expired ticket.
     * @throws AdukiException.Proof (`auth.dpop`) when this session is not
     * bound to the ticket's key; the ticket stays usable.
     */
    fun link(ticket: String): CenterView {
        require(ticket.isNotBlank()) { "ticket is required" }
        val body = JSONObject().put("ticket", ticket.trim()).toString()
        val response = call("id") { token ->
            Request.Builder().url(url("links")).header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON)).build()
        }
        return response.use { Center.view(Login.data(it, "Link")) }
    }

    /**
     * Unlinks the signed-in account from its Account Center (`DELETE
     * /v1/links {code}`); [code] is a fresh authenticator code. False when it
     * was in no center (`404`).
     */
    fun unlink(code: String): Boolean {
        val body = JSONObject().put("code", code.trim()).toString()
        val response = call("id") { token ->
            Request.Builder().url(url("links")).header("Authorization", "Bearer $token")
                .delete(body.toRequestBody(JSON)).build()
        }
        return response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 404 -> false
                !resp.isSuccessful -> Failures.raise(resp, raw, "Unlink")
                else -> true
            }
        }
    }

    /**
     * Ends the sessions of the device [group] (`DELETE /v1/sessions?group=`):
     * every app's session of this account on this device, this one included.
     * With [account] (a hex in this account's Account Center) only that
     * account's sessions of the group end ("remove from this device").
     * Returns how many ended; 0 when the group (or the account in it) has no
     * live session (`404`).
     */
    fun endGroup(group: String, account: String? = null): Int {
        require(group.isNotBlank()) { "group is required" }
        val target = url("sessions").toHttpUrl().newBuilder()
            .addQueryParameter("group", group.trim())
            .apply { account?.trim()?.ifBlank { null }?.let { addQueryParameter("account", it) } }
            .build()
        val response = call("id") { token ->
            Request.Builder().url(target).header("Authorization", "Bearer $token").delete().build()
        }
        return response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 404 -> 0
                !resp.isSuccessful -> Failures.raise(resp, raw, "Sign-out")
                else -> runCatching { JSONObject(Envelope.data(raw)).optInt("ended", 1) }.getOrDefault(1)
            }
        }
    }

    /**
     * Renames the signed-in account [hex] (`PATCH /v1/accounts/{hex}`
     * `{name}`): 1 to 80 characters, trimmed. Returns the name Aduki ID
     * stored. A tenant that locks profiles refuses a member with
     * [AdukiException.Api] `locked` (403); an invalid name is a 422
     * `input.invalid` (checked here first), an unknown account a 404 `entity.missing`.
     */
    fun rename(hex: String, name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > 80) {
            throw AdukiException.Api(422, "input.invalid", "A name is 1 to 80 characters")
        }
        if (hex.isBlank() || hex.contains('/')) throw AdukiException.Api(422, "input.invalid", "An account hex is required")
        val body = JSONObject().put("name", trimmed).toString()
        val response = call("id") { token ->
            Request.Builder()
                .url(url("accounts/$hex"))
                .header("Authorization", "Bearer $token")
                .patch(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
        return response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) Profiles.fail(resp, raw)
            val data = runCatching { JSONObject(Envelope.data(raw)) }.getOrNull()
                ?: throw AdukiException.Protocol("Unexpected rename response")
            data.optString("name").ifBlank { trimmed }
        }
    }

    private fun url(path: String) = "${base.trimEnd('/')}/$path"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * A single-use code from Aduki ID ([Id.handoff]) with which another app of the
 * same publisher opens its own session ([Login.redeem]). [expires] is in Unix
 * seconds on **this device's clock**: Aduki ID's `expires_in` added to the
 * local time when the answer came (a phone whose clock is wrong would
 * otherwise drop every code). It is a bearer secret until redeemed (unless
 * bound to the receiver's key): hand it only to [Id.handoff]'s target.
 */
data class Handoff(val code: String, val expires: Long) {
    /** True once [expires] has passed. */
    fun expired(now: Long = System.currentTimeMillis() / 1000): Boolean = expires in 1..now

    companion object {
        /** The default lifetime, seconds. */
        const val TTL = 3_600L

        /** The lifetime a broker asks for, seconds (ADK-AUTH-001 §10). */
        const val BROKER_TTL = 120L

        // The local expiry of a handoff answer: now + expires_in, else the
        // server's absolute expires (an older server).
        internal fun local(data: JSONObject, clock: () -> Long): Long {
            val lifetime = data.optLong("expires_in", -1)
            return if (lifetime >= 0) clock() / 1000 + lifetime else data.optLong("expires", 0)
        }
    }
}
