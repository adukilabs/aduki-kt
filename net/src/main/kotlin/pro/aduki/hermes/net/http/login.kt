package pro.aduki.hermes.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import pro.aduki.hermes.core.config.Endpoints
import pro.aduki.hermes.core.errors.HermesException
import java.io.IOException

/**
 * Type alias for Tokens.
 */
typealias Tokens = pro.aduki.hermes.core.models.Tokens

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

    /**
     * Signs in with a full address ([handle], e.g. `ada@aduki.me`), password
     * and a second factor: an authenticator [code] or a [backup] code.
     *
     * @throws HermesException.Unauthorized on a wrong password, a missing or
     * wrong second factor, or an inactive account.
     */
    fun submit(
        client: OkHttpClient,
        identity: String,
        handle: String,
        password: String,
        code: String? = null,
        backup: String? = null,
        audience: String = Endpoints.AUDIENCE
    ): Tokens {
        val root = JSONObject().apply {
            put("handle", handle)
            put("password", password)
            if (!code.isNullOrBlank()) put("code", code.trim())
            if (!backup.isNullOrBlank()) put("backup", backup.trim())
            put("audience", audience)
        }
        val request = Request.Builder()
            .url(url(identity, "sessions"))
            .post(root.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val data = data(response, "Sign-in")
            return Tokens(
                token = access(data, "sign-in"),
                refresh = required(data, "refresh", "sign-in"),
                expires = data.optLong("expires", 0).toString(),
                session = required(data, "session", "sign-in")
            )
        }
    }

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
                throw HermesException.Auth("Missing access token in refresh response", refresh = rotated)
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
        } catch (_: HermesException.Unauthorized) {
            // Spent, expired or revoked: the session is already unusable.
            return Signout(false, "")
        } catch (e: HermesException.Auth) {
            // A 2xx that failed validation: Aduki ID rotated the token, so the
            // old one is spent. Keep the new one if the answer carried it.
            return Signout(false, e.refresh)
        } catch (_: IOException) {
            return Signout(false, refreshToken)
        } catch (_: HermesException) {
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
        code: String
    ): Boolean {
        require(code.length == 6 && code.all { it.isDigit() }) { "TOTP code must be exactly 6 digits" }

        val url = "${endpoint.trimEnd('/')}/user/totp"
        val payload = JSONObject.quote(code) // Valid JSON string representation
        val authHeader = if (token.startsWith("hm_") || token.startsWith("key_")) "Key $token" else "Bearer $token"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .patch(payload.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw HermesException.Unauthorized("Session expired or unauthorized")
            }
            return response.isSuccessful
        }
    }

    private fun url(identity: String, path: String) = "${identity.trimEnd('/')}/$path"

    /** The `data` object of an Aduki ID envelope, or the matching exception. */
    private fun data(response: Response, what: String): JSONObject {
        val body = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (!response.isSuccessful) {
            val error = json?.optJSONObject("error")
            val kind = error?.optString("kind").orEmpty()
            val message = error?.optString("message").orEmpty().ifBlank { "HTTP ${response.code}" }
            if (response.code == 401) {
                throw HermesException.Unauthorized(if (kind.isBlank()) message else "$kind: $message")
            }
            throw HermesException.Network("$what failed: $message", code = response.code)
        }
        return json?.optJSONObject("data") ?: throw HermesException.Network("Empty response from $what")
    }

    private fun required(data: JSONObject, field: String, what: String): String {
        val value = data.optString(field, "")
        if (value.isBlank()) throw HermesException.Auth("Missing $field in $what response")
        return value
    }

    private fun access(data: JSONObject, what: String): String {
        val token = data.optString("access", "")
        if (token.isBlank()) throw HermesException.Auth("Missing access token in $what response")
        return token
    }
}
