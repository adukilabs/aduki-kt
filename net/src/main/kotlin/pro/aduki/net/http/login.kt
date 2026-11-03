package pro.aduki.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.SignedIn
import java.io.IOException

/**
 * Type alias for Tokens.
 */
typealias Tokens = pro.aduki.core.models.Tokens

/**
 * The outcome of [Login.logout]. When [revoked] is false, [refresh] is the
 * refresh token that is still good for a retry (the rotated one if the swap
 * went through), or blank when there is nothing left to retry with.
 */
data class Signout(val revoked: Boolean, val refresh: String)

/**
 * Login signs in at Aduki ID and keeps that sign-in fresh.
 *
 * Mail no longer runs its own sign-in: the SDK signs in at Aduki ID
 * (`POST /v1/sessions`) for an access token with audience `mail`, renews it
 * with the refresh token (`POST /v1/tokens`) and signs out by revoking the
 * session (`DELETE /v1/sessions/{hex}`). The `identity` argument is the Aduki
 * ID base, e.g. `https://id.aduki.pro/v1`.
 */
object Login {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** The longest device name sent; Aduki ID lists it with the session. */
    const val DEVICE = 128

    /**
     * Signs in with a full address ([handle], e.g. `ada@aduki.me`), password
     * and a second factor: an authenticator [code] or a [backup] code.
     * [device] names the session in the account's session list (e.g.
     * "Mail on Pixel 9"); it is trimmed and cut to [DEVICE] characters.
     *
     * @throws AdukiException.Unauthorized on a wrong password, a missing or
     * wrong second factor, or an inactive account.
     */
    fun submit(
        client: OkHttpClient,
        identity: String,
        handle: String,
        password: String,
        code: String? = null,
        backup: String? = null,
        audience: String = Endpoints.AUDIENCE,
        device: String? = null
    ): Tokens = signIn(client, identity, handle, password, code, backup, audience, device).tokens

    /**
     * [submit] with the whole answer: the tokens, the session's device group
     * and, with [link] true, a single-use link ticket (`adkl_...`, 10 minutes)
     * with which the account already signed in on this app adds this one to
     * its Account Center (`Center.link(ticket)`). [link] needs a DPoP-signing
     * [client]: the ticket is bound to its key (`422 input.invalid` otherwise).
     *
     * @throws AdukiException.Unauthorized on a wrong password, a missing or
     * wrong second factor (`auth.factor`), or an inactive account.
     * @throws AdukiException.Proof when the DPoP proof was refused.
     */
    fun signIn(
        client: OkHttpClient,
        identity: String,
        handle: String,
        password: String,
        code: String? = null,
        backup: String? = null,
        audience: String = Endpoints.AUDIENCE,
        device: String? = null,
        link: Boolean = false
    ): SignedIn {
        val root = JSONObject().apply {
            put("handle", handle)
            put("password", password)
            if (!code.isNullOrBlank()) put("code", code.trim())
            if (!backup.isNullOrBlank()) put("backup", backup.trim())
            put("audience", audience)
            device?.trim()?.take(DEVICE)?.ifBlank { null }?.let { put("device", it) }
            if (link) put("link", true)
        }
        val request = Request.Builder()
            .url(url(identity, "sessions"))
            .post(root.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            return opened(data(response, "Sign-in"), "sign-in")
        }
    }

    /** The [SignedIn] of a sign-in or redeem answer. */
    internal fun opened(data: JSONObject, what: String): SignedIn = SignedIn(
        tokens = Tokens(
            token = access(data, what),
            refresh = required(data, "refresh", what),
            expires = data.optLong("expires", 0).toString(),
            session = required(data, "session", what)
        ),
        group = text(data, "group"),
        ticket = text(data, "ticket")
    )

    /** A string field, null when absent, JSON null or blank. */
    internal fun text(data: JSONObject, field: String): String? =
        if (data.isNull(field)) null else data.optString(field).ifBlank { null }

    /**
     * Exchanges a refresh token for a new access token and a rotated refresh
     * token. The old refresh token must not be used again.
     */
    fun refresh(
        client: OkHttpClient,
        identity: String,
        refreshToken: String,
        audience: String = Endpoints.AUDIENCE
    ): Tokens {
        val root = JSONObject().apply {
            put("refresh", refreshToken)
            put("audience", audience)
        }
        val request = Request.Builder()
            .url(url(identity, "tokens"))
            .post(root.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val data = data(response, "Token refresh")
            // Read the rotated token first: the old one is spent once Aduki ID
            // answered 2xx, so a missing access token must not lose it.
            val rotated = required(data, "refresh", "refresh")
            val token = data.optString("access", "")
            if (token.isBlank()) {
                throw AdukiException.Auth("Missing access token in refresh response", refresh = rotated)
            }
            return Tokens(
                token = token,
                refresh = rotated,
                expires = data.optLong("expires", 0).toString()
            )
        }
    }

    /**
     * Signs out: revokes [session] at Aduki ID, which ends every token issued
     * from it, mail's included.
     *
     * Aduki ID only accepts its own (`id` audience) tokens here, so this first
     * swaps the refresh token for one. That swap spends [refreshToken], so a
     * failed revocation hands back the rotated token for a retry.
     */
    fun logout(
        client: OkHttpClient,
        identity: String,
        session: String,
        refreshToken: String
    ): Signout {
        if (session.isBlank() || refreshToken.isBlank()) return Signout(false, refreshToken)
        val own = try {
            refresh(client, identity, refreshToken, "id")
        } catch (e: AdukiException.Unauthorized) {
            // Spent, expired or revoked: the session is already unusable.
            return Signout(false, if (e.ended) "" else refreshToken)
        } catch (e: AdukiException.Auth) {
            // A 2xx that failed validation: Aduki ID rotated the token, so the
            // old one is spent. Keep the new one if the answer carried it.
            return Signout(false, e.refresh)
        } catch (_: IOException) {
            return Signout(false, refreshToken)
        } catch (_: AdukiException) {
            // A non-2xx answer (429, 5xx): the token was not used up.
            return Signout(false, refreshToken)
        }
        val request = Request.Builder()
            .url(url(identity, "sessions/$session"))
            .header("Authorization", "Bearer ${own.token}")
            .delete()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Signout(true, "") else Signout(false, own.refresh)
            }
        } catch (_: IOException) {
            Signout(false, own.refresh)
        }
    }

    /**
     * Configures or confirms 6-digit TOTP secret via PATCH /user/totp.
     */
    @Deprecated("Second factors are managed in Aduki ID's Account Center; mail's /user/totp is going away.")
    fun totp(
        client: OkHttpClient,
        endpoint: String,
        token: String,
        code: String,
        apiKey: Boolean = false
    ): Boolean {
        require(code.length == 6 && code.all { it.isDigit() }) { "TOTP code must be exactly 6 digits" }

        val url = "${endpoint.trimEnd('/')}/user/totp"
        val payload = JSONObject.quote(code) // Valid JSON string representation
        val authHeader = Scheme.header(token, apiKey)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .patch(payload.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw AdukiException.Unauthorized("Session expired or unauthorized")
            }
            return response.isSuccessful
        }
    }

    private fun url(identity: String, path: String) = "${identity.trimEnd('/')}/$path"

    /** The `data` object of an Aduki ID envelope, or the matching exception. */
    /**
     * Opens this app's own session from a [Handoff] another app of the same
     * publisher obtained ([Id.handoff]) and left for it: `POST /v1/handoffs/redeem`.
     * The session is new, of the same account, bound to this app's DPoP key
     * when [client] carries one; no password or second factor is asked. A
     * handoff works once.
     *
     * @throws AdukiException.Unauthorized when the handoff is unknown, used,
     * expired or the session that issued it has ended.
     */
    fun redeem(
        client: OkHttpClient,
        identity: String,
        handoff: String,
        audience: String = Endpoints.AUDIENCE,
        device: String? = null
    ): Tokens = open(client, identity, handoff, audience, device).tokens

    /**
     * [redeem] with the whole answer: the tokens and the device group the new
     * session joined (the group of the session that asked for the code).
     *
     * @throws AdukiException.Unauthorized (`auth.invalid`) when the code is
     * unknown, used, expired or its issuing session has ended.
     * @throws AdukiException.Proof (`auth.dpop`) when the code is bound to a
     * key and [client] did not prove it: the code stays usable.
     */
    fun open(
        client: OkHttpClient,
        identity: String,
        handoff: String,
        audience: String = Endpoints.AUDIENCE,
        device: String? = null
    ): SignedIn {
        val root = JSONObject().apply {
            put("handoff", handoff.trim())
            put("audience", audience)
            device?.trim()?.take(DEVICE)?.ifBlank { null }?.let { put("device", it) }
        }
        val request = Request.Builder()
            .url(url(identity, "handoffs/redeem"))
            .post(root.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            return opened(data(response, "Handoff"), "handoff")
        }
    }

    internal fun data(response: Response, what: String): JSONObject {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) Failures.raise(response, body, what)
        val json = runCatching { JSONObject(body) }.getOrNull()
        return json?.optJSONObject("data") ?: throw AdukiException.Network("Empty response from $what")
    }

    internal fun required(data: JSONObject, field: String, what: String): String {
        val value = data.optString(field, "")
        if (value.isBlank()) throw AdukiException.Auth("Missing $field in $what response")
        return value
    }

    internal fun access(data: JSONObject, what: String): String {
        val token = data.optString("access", "")
        if (token.isBlank()) throw AdukiException.Auth("Missing access token in $what response")
        return token
    }
}
