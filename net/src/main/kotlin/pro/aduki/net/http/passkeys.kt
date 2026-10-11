package pro.aduki.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Tokens
import java.io.IOException
import java.time.Instant

/**
 * Passkeys is the protocol side of WebAuthn at Aduki ID (ADK-KT-003 K6,
 * `docs/src/auth/passkeys.md` of the `id` repo): the begin and verify calls of
 * the three ceremonies (register, step-up, sign-in) and the list and removal
 * of one's passkeys. It runs on a plain JVM.
 *
 * The authenticator step is the platform's (Android Credential Manager, not in
 * this module): pass [Challenge.optionsJson] to
 * `CreatePublicKeyCredentialRequest` / `GetPublicKeyCredentialOption`, and hand
 * the JSON the platform returns (`registrationResponseJson`,
 * `authenticationResponseJson`) to the matching finish call. [credential]
 * checks and trims it to what Aduki ID reads; a challenge is spent by the
 * first answer, right or wrong, and lasts five minutes.
 *
 * `identity` is the Aduki ID base, e.g. `https://id.aduki.pro/v1`; `bearer`
 * an access token for audience `id`. Never run against a real server yet.
 */
object Passkeys {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** A ceremony in flight: [ceremony] is echoed to the finish call, [options] go to the platform. */
    class Challenge(val ceremony: String, val expires: Instant, val options: JSONObject) {
        /** The WebAuthn options JSON for the platform API. */
        val optionsJson: String get() = options.toString()
    }

    /** A registered passkey (`eligible`: may be backed up; `synced`: is backed up). */
    class Registered(val passkey: String, val label: String, val eligible: Boolean, val synced: Boolean, val format: String)

    /** One of the caller's passkeys (never key material). */
    class Item(val passkey: String, val label: String?, val eligible: Boolean, val synced: Boolean, val created: String, val used: String?)

    /**
     * Checks a WebAuthn credential JSON as the platform returns it and keeps
     * only what Aduki ID reads: `id`, `type` (`public-key`) and the `response`
     * fields `clientDataJSON`, `attestationObject`, `authenticatorData`,
     * `signature`, `userHandle`. A registration needs `attestationObject`; an
     * assertion needs `authenticatorData` and `signature`.
     *
     * @throws IllegalArgumentException when the credential is malformed.
     */
    fun credential(json: String, registration: Boolean): JSONObject {
        val source = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("Credential is not JSON", e) }
        val id = source.optString("id")
        require(id.isNotBlank() && id.all(::urlSafe)) { "Credential id must be base64url" }
        require(source.optString("type") == "public-key") { "Credential type must be public-key" }
        val response = source.optJSONObject("response") ?: throw IllegalArgumentException("Credential has no response")
        require(response.optString("clientDataJSON").isNotBlank()) { "Response has no clientDataJSON" }
        if (registration) {
            require(response.optString("attestationObject").isNotBlank()) { "Registration has no attestationObject" }
        } else {
            require(response.optString("authenticatorData").isNotBlank()) { "Assertion has no authenticatorData" }
            require(response.optString("signature").isNotBlank()) { "Assertion has no signature" }
        }
        val kept = JSONObject()
        for (field in RESPONSE) if (response.has(field) && !response.isNull(field)) kept.put(field, response.get(field))
        return JSONObject().put("id", id).put("type", "public-key").put("response", kept)
    }

    /** Begins adding a passkey (needs a recent step-up, or a fresh authenticator [code]). */
    fun beginRegistration(client: OkHttpClient, identity: String, bearer: String, label: String? = null, code: String? = null): Challenge {
        val body = JSONObject().apply {
            if (!label.isNullOrBlank()) put("label", label.trim())
            if (!code.isNullOrBlank()) put("code", code.trim())
        }
        return challenge(call(client, post(identity, "passkeys/registrations", bearer, body), "Passkey registration"))
    }

    /** Finishes adding a passkey with the platform's `registrationResponseJson`. */
    fun finishRegistration(client: OkHttpClient, identity: String, bearer: String, challenge: Challenge, credentialJson: String): Registered {
        val body = JSONObject().put("credential", credential(credentialJson, registration = true))
        val data = call(client, post(identity, "passkeys/registrations/${challenge.ceremony}/verifications", bearer, body), "Passkey registration")
        return Registered(
            passkey = data.optString("passkey").ifBlank { throw AdukiException.Protocol("Missing passkey in response") },
            label = data.optString("label"),
            eligible = data.optBoolean("eligible"),
            synced = data.optBoolean("synced"),
            format = data.optString("format")
        )
    }

    /** Begins a step-up with one of the caller's passkeys (409 when it has none). */
    fun beginStepUp(client: OkHttpClient, identity: String, bearer: String): Challenge =
        challenge(call(client, post(identity, "passkeys/stepups", bearer, JSONObject()), "Passkey step-up"))

    /**
     * Finishes a step-up; returns the Unix seconds of the new step-up. Renew
     * the access token afterwards to receive the new `stp` claim.
     */
    fun finishStepUp(client: OkHttpClient, identity: String, bearer: String, challenge: Challenge, credentialJson: String): Long {
        val body = JSONObject().put("credential", credential(credentialJson, registration = false))
        val data = call(client, post(identity, "passkeys/stepups/${challenge.ceremony}/verifications", bearer, body), "Passkey step-up")
        if (!data.has("stepped")) throw AdukiException.Protocol("Missing stepped in response")
        return data.getLong("stepped")
    }

    /** Begins a passkey-first sign-in: no account is named, no credential is needed. */
    fun beginSignIn(client: OkHttpClient, identity: String): Challenge =
        challenge(call(client, post(identity, "passkeys/signins", null, JSONObject()), "Passkey sign-in"))

    /**
     * Finishes a sign-in with the platform's `authenticationResponseJson`: one
     * assertion is password and second factor at once. Every failure is
     * [AdukiException.Unauthorized]. With a DPoP-signing [client] the session
     * is bound to that key.
     */
    fun finishSignIn(
        client: OkHttpClient,
        identity: String,
        challenge: Challenge,
        credentialJson: String,
        audience: String = Endpoints.AUDIENCE,
        device: String? = null
    ): Tokens {
        val body = JSONObject().put("credential", credential(credentialJson, registration = false)).put("audience", audience)
        if (!device.isNullOrBlank()) body.put("device", device)
        val data = call(client, post(identity, "passkeys/signins/${challenge.ceremony}/verifications", null, body), "Passkey sign-in")
        fun need(field: String) = data.optString(field).ifBlank { throw AdukiException.Auth("Missing $field in passkey sign-in response") }
        return Tokens(token = need("access"), refresh = need("refresh"), expires = data.optLong("expires", 0).toString(), session = need("session"))
    }

    /** The caller's passkeys. */
    fun list(client: OkHttpClient, identity: String, bearer: String): List<Item> {
        val request = Request.Builder().url(url(identity, "passkeys")).header("Authorization", "Bearer $bearer").get().build()
        val items = array(request, client, "Passkey list")
        return (0 until items.length()).map {
            val o = items.getJSONObject(it)
            Item(
                o.optString("passkey"), o.optString("label").ifBlank { null }, o.optBoolean("eligible"),
                o.optBoolean("synced"), o.optString("created"), o.optString("used").ifBlank { null }
            )
        }
    }

    /** Removes one of the caller's passkeys (needs a recent step-up, or a fresh authenticator [code]). */
    fun remove(client: OkHttpClient, identity: String, bearer: String, passkey: String, code: String? = null) {
        val body = JSONObject().apply { if (!code.isNullOrBlank()) put("code", code.trim()) }
        val request = Request.Builder().url(url(identity, "passkeys/$passkey")).header("Authorization", "Bearer $bearer")
            .delete(body.toString().toRequestBody(JSON)).build()
        send(request, client, "Passkey removal") { /* 204, no body */ }
    }

    /** True for the error of a route that needs a step-up first (`403 auth.stepup`): step up, then retry. */
    fun needsStepUp(e: Throwable): Boolean = e is AdukiException.Network && e.code == 403 && e.message.orEmpty().contains("auth.stepup")

    private fun challenge(data: JSONObject): Challenge {
        val ceremony = data.optString("ceremony").ifBlank { throw AdukiException.Protocol("Missing ceremony in response") }
        val options = data.optJSONObject("options") ?: throw AdukiException.Protocol("Missing options in response")
        if (options.optString("challenge").isBlank()) throw AdukiException.Protocol("Options carry no challenge")
        val expires = runCatching { Instant.parse(data.optString("expires")) }.getOrElse {
            throw AdukiException.Protocol("Bad expires in response", it)
        }
        return Challenge(ceremony, expires, options)
    }

    private fun post(identity: String, path: String, bearer: String?, body: JSONObject): Request =
        Request.Builder().url(url(identity, path)).post(body.toString().toRequestBody(JSON))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()

    private fun call(client: OkHttpClient, request: Request, what: String): JSONObject =
        send(request, client, what) { body ->
            runCatching { JSONObject(body) }.getOrNull()?.optJSONObject("data")
                ?: throw AdukiException.Network("Empty response from $what")
        }

    private fun array(request: Request, client: OkHttpClient, what: String): JSONArray =
        send(request, client, what) { body ->
            runCatching { JSONObject(body) }.getOrNull()?.optJSONArray("data")
                ?: throw AdukiException.Network("Empty response from $what")
        }

    private fun <T> send(request: Request, client: OkHttpClient, what: String, parse: (String) -> T): T =
        try {
            client.newCall(request).execute().use { response -> parse(checked(response, what)) }
        } catch (e: IOException) {
            throw AdukiException.Network("$what failed: ${e.message}", e)
        }

    private fun checked(response: Response, what: String): String {
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) return body
        val error = runCatching { JSONObject(body) }.getOrNull()?.optJSONObject("error")
        val kind = error?.optString("kind").orEmpty()
        val message = error?.optString("message").orEmpty().ifBlank { "HTTP ${response.code}" }
        val text = if (kind.isBlank()) message else "$kind: $message"
        if (response.code == 401) throw AdukiException.Unauthorized(text)
        throw AdukiException.Network("$what failed: $text", code = response.code)
    }

    private fun url(identity: String, path: String) = "${identity.trimEnd('/')}/$path"

    private fun urlSafe(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_'

    private val RESPONSE = listOf("clientDataJSON", "attestationObject", "authenticatorData", "signature", "userHandle")
}
