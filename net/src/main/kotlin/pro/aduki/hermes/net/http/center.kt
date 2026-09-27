package pro.aduki.hermes.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.hermes.core.config.Endpoints
import pro.aduki.hermes.core.errors.HermesException
import pro.aduki.hermes.core.models.Linked
import pro.aduki.hermes.core.models.Switcher
import pro.aduki.hermes.core.models.Tokens
import pro.aduki.hermes.core.models.Unlocked
import java.util.Base64

/**
 * Center is the Account Center at Aduki ID (ADK-AUTH-002 §5): link another
 * account, read the switcher, unlink, and reopen sessions with a
 * device-bound key (ADK-AUTH-001 §9, §5.4) instead of password and TOTP.
 *
 * `identity` is the Aduki ID base, e.g. `https://id.aduki.pro/v1`; `bearer`
 * is an access token for audience `id`. The device key never leaves the
 * platform keystore: [unlock] takes a signer.
 */
object Center {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val B64 = Base64.getUrlEncoder().withoutPadding()
    private val UNB64 = Base64.getUrlDecoder()

    /**
     * Links the account [handle] to the signed-in one. Its own password and
     * second factor ([code] or [backup]) are required.
     *
     * @throws HermesException.Unauthorized for a wrong password or factor.
     * @throws HermesException.Network with code 403 when a tenant forbids
     * links, 409 when the center would hold two personal accounts.
     */
    fun link(
        client: OkHttpClient,
        identity: String,
        bearer: String,
        handle: String,
        password: String,
        code: String? = null,
        backup: String? = null
    ): Switcher {
        val body = JSONObject().apply {
            put("handle", handle)
            put("password", password)
            if (!code.isNullOrBlank()) put("code", code.trim())
            if (!backup.isNullOrBlank()) put("backup", backup.trim())
        }
        val request = Request.Builder()
            .url(url(identity, "links"))
            .header("Authorization", "Bearer $bearer")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { return switcher(data(it, "Link")) }
    }

    /** The signed-in account's center, or `null` when it is in none. */
    fun fetch(client: OkHttpClient, identity: String, bearer: String): Switcher? {
        val request = Request.Builder()
            .url(url(identity, "center"))
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        client.newCall(request).execute().use {
            if (it.code == 404) return null
            return switcher(data(it, "Center"))
        }
    }

    /** Unlinks the signed-in account; [code] is a fresh TOTP code. */
    fun unlink(client: OkHttpClient, identity: String, bearer: String, code: String): Boolean {
        val request = Request.Builder()
            .url(url(identity, "links"))
            .header("Authorization", "Bearer $bearer")
            .delete(JSONObject().put("code", code.trim()).toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use {
            if (it.code == 404) return false
            if (!it.isSuccessful) data(it, "Unlink")
            return true
        }
    }

    /**
     * Registers this device's Ed25519 public key (32 bytes) for the signed-in
     * account, or for its center when [center] is true. Returns the
     * credential hex to keep with the key. [code] is a fresh TOTP code.
     */
    fun register(
        client: OkHttpClient,
        identity: String,
        bearer: String,
        device: String,
        public: ByteArray,
        code: String,
        center: Boolean = false
    ): String {
        require(public.size == 32) { "an Ed25519 public key is 32 bytes" }
        val body = JSONObject()
            .put("device", device)
            .put("public", B64.encodeToString(public))
            .put("center", center)
            .put("code", code.trim())
        val request = Request.Builder()
            .url(url(identity, "credentials"))
            .header("Authorization", "Bearer $bearer")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use {
            val data = data(it, "Credential")
            return data.optString("credential").ifBlank {
                throw HermesException.Protocol("Missing credential in response")
            }
        }
    }

    /**
     * Reopens sessions with the device key: fetches a nonce for
     * [credential], has [sign] sign it (after the OS biometric prompt), and
     * returns one session per account it opened.
     *
     * @throws HermesException.Unauthorized when the signature or nonce is
     * refused.
     * @throws HermesException.Network with code 404 when the credential is
     * revoked or past its trust window: sign in fully.
     */
    fun unlock(
        client: OkHttpClient,
        identity: String,
        credential: String,
        sign: (ByteArray) -> ByteArray,
        audience: String = Endpoints.AUDIENCE
    ): List<Unlocked> {
        val challenge = Request.Builder()
            .url(url(identity, "credentials/$credential/challenges"))
            .post("{}".toRequestBody(JSON))
            .build()
        val nonce = client.newCall(challenge).execute().use { data(it, "Challenge").optString("nonce") }
        val signature = sign(UNB64.decode(nonce))
        val body = JSONObject()
            .put("nonce", nonce)
            .put("signature", B64.encodeToString(signature))
            .put("audience", audience)
        val request = Request.Builder()
            .url(url(identity, "credentials/$credential/unlock"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use {
            val list = list(it, "Unlock")
            return (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                Unlocked(
                    account = o.optString("account"),
                    tokens = Tokens(
                        token = o.optString("access"),
                        refresh = o.optString("refresh"),
                        expires = o.optLong("expires", 0).toString(),
                        session = o.optString("session")
                    )
                )
            }
        }
    }

    /** The switcher in a center-token response. */
    internal fun switcher(data: JSONObject): Switcher {
        val token = data.optString("token")
        return Switcher(
            center = data.optString("center"),
            token = token,
            expires = data.optLong("expires", 0),
            accounts = accounts(token)
        )
    }

    /** The accounts listed in a center token's payload (`acc`). */
    internal fun accounts(token: String): List<Linked> {
        val payload = token.split('.').getOrNull(1) ?: return emptyList()
        val claims = runCatching { JSONObject(String(UNB64.decode(payload))) }.getOrNull()
            ?: return emptyList()
        val acc = claims.optJSONArray("acc") ?: JSONArray()
        return (0 until acc.length()).map { i ->
            val o = acc.getJSONObject(i)
            Linked(
                hex = o.optString("hex"),
                handle = o.optString("handle"),
                personal = o.optString("knd") == "p",
                tenant = o.optString("ten").ifBlank { null },
                unlock = o.optString("unlock", "self")
            )
        }
    }

    private fun url(identity: String, path: String) = "${identity.trimEnd('/')}/$path"

    private fun envelope(response: Response, what: String): JSONObject {
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
        return json ?: throw HermesException.Network("Empty response from $what")
    }

    private fun data(response: Response, what: String): JSONObject =
        envelope(response, what).optJSONObject("data")
            ?: throw HermesException.Network("Empty response from $what")

    private fun list(response: Response, what: String): JSONArray =
        envelope(response, what).optJSONArray("data")
            ?: throw HermesException.Network("Empty response from $what")
}
