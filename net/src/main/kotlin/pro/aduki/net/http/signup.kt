package pro.aduki.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException

/** Server parameters and verification requirements for signup. */
data class Requirements(val phone: Boolean, val domain: String)

/** Pending account creation outcome: account identifier and TOTP enrolment details. */
data class Enrolment(val id: String, val handle: String, val uri: String, val secret: String)

/**
 * Signup implements the staged registration flow against Aduki ID:
 * 1. Requirements discovery (`GET /v1/accounts`).
 * 2. Stage 1 account creation (`POST /v1/accounts`).
 * 3. Stage 2 second-factor verification (`POST /v1/accounts/{hex}/verifications`).
 */
object Signup {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Discovers personal signup requirements from Aduki ID.
     */
    fun requirements(client: OkHttpClient, identity: String = Endpoints.ID): Requirements {
        val request = Request.Builder()
            .url(url(identity, "accounts"))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val data = data(response, "Requirements discovery")
            return Requirements(
                phone = data.optBoolean("phone", false),
                domain = required(data, "domain", "requirements")
            )
        }
    }

    /**
     * Stage 1: Initiates personal account registration.
     * The account remains pending until confirmed by [verify].
     */
    fun create(
        client: OkHttpClient,
        identity: String,
        handle: String,
        name: String,
        password: String,
        phone: String? = null
    ): Enrolment {
        val root = JSONObject().apply {
            put("handle", handle.trim())
            put("name", name.trim())
            put("password", password)
            if (!phone.isNullOrBlank()) put("phone", phone.trim())
        }
        val request = Request.Builder()
            .url(url(identity, "accounts"))
            .post(root.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val data = data(response, "Account creation")
            val totp = data.optJSONObject("totp")
                ?: throw AdukiException.Auth("Missing totp in account creation response")
            return Enrolment(
                id = required(data, "id", "account creation"),
                handle = required(data, "handle", "account creation"),
                uri = required(totp, "uri", "totp enrolment"),
                secret = required(totp, "secret", "totp enrolment")
            )
        }
    }

    /**
     * Stage 2: Confirms the authenticator code, activating the account.
     * Returns the one-time backup codes.
     */
    fun verify(
        client: OkHttpClient,
        identity: String,
        hex: String,
        password: String,
        code: String
    ): List<String> {
        val root = JSONObject().apply {
            put("password", password)
            put("code", code.trim())
        }
        val request = Request.Builder()
            .url(url(identity, "accounts/${hex.trim()}/verifications"))
            .post(root.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val data = data(response, "Account verification")
            val array = data.optJSONArray("codes")
            return if (array == null) {
                emptyList()
            } else {
                List(array.length()) { i -> array.getString(i) }
            }
        }
    }

    private fun url(identity: String, path: String) = "${identity.trimEnd('/')}/$path"

    private fun data(response: Response, what: String): JSONObject {
        val body = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (!response.isSuccessful) {
            val error = json?.optJSONObject("error")
            val kind = error?.optString("kind").orEmpty()
            val message = error?.optString("message").orEmpty().ifBlank { "HTTP ${response.code}" }
            if (response.code == 401) {
                throw AdukiException.Unauthorized(if (kind.isBlank()) message else "$kind: $message")
            }
            throw AdukiException.Network("$what failed: $message", code = response.code)
        }
        return json?.optJSONObject("data") ?: throw AdukiException.Network("Empty response from $what")
    }

    private fun required(data: JSONObject, field: String, what: String): String {
        val value = data.optString(field, "")
        if (value.isBlank()) throw AdukiException.Auth("Missing $field in $what response")
        return value
    }
}
