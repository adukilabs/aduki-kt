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
import pro.aduki.core.models.AccountProfile
import pro.aduki.core.models.CenterView
import pro.aduki.core.models.Tenancy
import pro.aduki.core.models.Tokens
import pro.aduki.core.models.Unlocked
import pro.aduki.crypto.dpop.Device
import pro.aduki.crypto.dpop.Key
import java.util.Base64

/**
 * Center is the Account Center at Aduki ID (ADK-AUTH-002 §5): link another
 * account, read the center, unlink, and reopen sessions with a
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
     * second factor ([code] or [backup]) are required. Returns the center
     * with every account's profile.
     *
     * @throws AdukiException.Unauthorized for a wrong password or factor.
     * @throws AdukiException.Network with code 403 when a tenant forbids
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
    ): CenterView {
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
        client.newCall(request).execute().use { return view(data(it, "Link")) }
    }

    /**
     * Links the account a link [ticket] names (from a sign-in of it with
     * `link: true` in this app) to the signed-in one: `POST /v1/links
     * {ticket}`. [client] must sign DPoP proofs with the key the ticket and
     * the [bearer]'s session are bound to.
     *
     * @throws AdukiException.Network with code 403 when a tenant forbids
     * links, 409 when the center would hold two personal accounts.
     * @throws AdukiException.Unauthorized for an unknown, used or expired ticket.
     * @throws AdukiException.Proof when the session is not bound to the
     * ticket's key (the ticket stays usable).
     */
    fun link(client: OkHttpClient, identity: String, bearer: String, ticket: String): CenterView {
        require(ticket.isNotBlank()) { "ticket is required" }
        val request = Request.Builder()
            .url(url(identity, "links"))
            .header("Authorization", "Bearer $bearer")
            .post(JSONObject().put("ticket", ticket.trim()).toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { return view(data(it, "Link")) }
    }

    /**
     * The signed-in account's center with every account's profile
     * (`GET /v1/center`). An account in no center gets itself alone with
     * [CenterView.center] null; an older server's `404` reads the same way
     * with no accounts.
     */
    fun view(client: OkHttpClient, identity: String, bearer: String): CenterView {
        val request = Request.Builder()
            .url(url(identity, "center"))
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        client.newCall(request).execute().use {
            if (it.code == 404) return CenterView(null, "", 0, emptyList())
            return view(data(it, "Center"))
        }
    }

    /** The [CenterView] of a center answer (`{center, token, expires, accounts}`). */
    fun view(data: JSONObject): CenterView {
        val token = data.optString("token")
        val listed = data.optJSONArray("accounts")
        val accounts = if (listed != null) {
            (0 until listed.length()).mapNotNull { listed.optJSONObject(it)?.let(::profile) }
        } else {
            // An older server: the accounts only in the token.
            accounts(token)
        }
        return CenterView(
            center = Login.text(data, "center"),
            token = token,
            expires = data.optLong("expires", 0),
            accounts = accounts
        )
    }

    /**
     * One account of a center answer, or the `GET /v1/me` answer: `hex`,
     * `handle`, `name`, `kind` (or the token's `knd`), `tenant`, `photo`,
     * `center`, `unlock`, `links`, `session`, `group`.
     */
    fun profile(o: JSONObject): AccountProfile {
        val tenant = o.optJSONObject("tenant")?.let { t ->
            Tenancy(
                hex = t.optString("hex"),
                name = t.optString("name"),
                role = Login.text(t, "role"),
                owner = t.optBoolean("owner", false)
            )
        }
        return AccountProfile(
            hex = o.optString("hex"),
            handle = o.optString("handle"),
            name = Login.text(o, "name").orEmpty(),
            kind = AccountProfile.Kind.of(Login.text(o, "kind") ?: Login.text(o, "knd") ?: if (tenant != null) "member" else null),
            tenant = tenant,
            photo = Login.text(o, "photo"),
            center = Login.text(o, "center"),
            unlock = Login.text(o, "unlock") ?: "self",
            links = Login.text(o, "links") ?: "allow",
            session = Login.text(o, "session"),
            group = Login.text(o, "group")
        )
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
                throw AdukiException.Protocol("Missing credential in response")
            }
        }
    }

    /**
     * Registers [key] as a device credential (Ed25519 or ES256). The algorithm
     * and the public key encoding come from the key (see [Device]); for ES256
     * the request carries `alg: "ES256"` and the 65-byte uncompressed point.
     * Returns the credential hex to keep with the key.
     *
     * @throws AdukiException.Unauthorized for a wrong [code].
     * @throws AdukiException.Network with code 422 when the server refuses the
     * key or the label (an old server that does not know ES256 answers so),
     * 404 when [center] is true and the account is in none.
     * @throws AdukiException.Protocol when the answer names another algorithm
     * than the key's.
     */
    fun register(
        client: OkHttpClient,
        identity: String,
        bearer: String,
        device: String,
        key: Key,
        code: String,
        center: Boolean = false
    ): String {
        val alg = Device.alg(key)
        val body = JSONObject()
            .put("device", device)
            .put("alg", alg)
            .put("public", B64.encodeToString(Device.public(key)))
            .put("center", center)
            .put("code", code.trim())
        val request = Request.Builder()
            .url(url(identity, "credentials"))
            .header("Authorization", "Bearer $bearer")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use {
            val data = data(it, "Credential")
            // The server pins the credential to the algorithm it was told; anything else means it did not understand.
            val answered = data.optString("alg")
            if (answered != alg) throw AdukiException.Protocol("Server registered algorithm '$answered', not $alg")
            return data.optString("credential").ifBlank {
                throw AdukiException.Protocol("Missing credential in response")
            }
        }
    }

    /**
     * Revokes a device credential (`DELETE /credentials/{id}`). Returns false
     * when it was already gone (404).
     */
    fun revoke(client: OkHttpClient, identity: String, bearer: String, credential: String): Boolean {
        val request = Request.Builder()
            .url(url(identity, "credentials/$credential"))
            .header("Authorization", "Bearer $bearer")
            .delete()
            .build()
        client.newCall(request).execute().use {
            if (it.code == 404) return false
            if (!it.isSuccessful) envelope(it, "Revoke")
            return true
        }
    }

    /**
     * Reopens sessions with the device key: fetches a nonce for
     * [credential], has [sign] sign it (after the OS biometric prompt), and
     * returns one session per account it opened.
     *
     * One attempt, no retry: a refused signature is a 401 and the nonce it used
     * is not offered again; the next call asks for a fresh nonce. [sign]
     * signs the raw nonce bytes and returns the 64-byte signature (Ed25519, or
     * ES256 as `r || s`; see the [Key] overload).
     *
     * @throws AdukiException.Unauthorized when the signature or nonce is
     * refused (`401 auth.invalid`, the same for every cause).
     * @throws AdukiException.Network with code 404 when the credential is
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
        if (nonce.isBlank()) throw AdukiException.Protocol("Missing nonce in response")
        val raw = try { UNB64.decode(nonce) } catch (e: IllegalArgumentException) {
            throw AdukiException.Protocol("Nonce is not base64url", e)
        }
        val signature = sign(raw)
        require(signature.size == Device.SIGNATURE) {
            "A device signature is ${Device.SIGNATURE} bytes (ES256 as r||s, not DER); got ${signature.size}"
        }
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

    /**
     * [unlock] with a [Key] as the signer: the nonce is signed with
     * [Key.sign] (Ed25519, or ES256 as 64-byte `r || s`, which `AndroidKey`
     * and `Software` both return). Pass the key whose public part was
     * registered with [register]; a different key is a 401.
     */
    fun unlock(
        client: OkHttpClient,
        identity: String,
        credential: String,
        key: Key,
        audience: String = Endpoints.AUDIENCE
    ): List<Unlocked> {
        Device.alg(key)
        return unlock(client, identity, credential, key::sign, audience)
    }

    /** The accounts listed in a center token's payload (`acc`), with what the token says of each. */
    internal fun accounts(token: String): List<AccountProfile> {
        val payload = token.split('.').getOrNull(1) ?: return emptyList()
        val claims = runCatching { JSONObject(String(UNB64.decode(payload))) }.getOrNull()
            ?: return emptyList()
        val acc = claims.optJSONArray("acc") ?: JSONArray()
        return (0 until acc.length()).map { i ->
            val o = acc.getJSONObject(i)
            AccountProfile(
                hex = o.optString("hex"),
                handle = o.optString("handle"),
                kind = if (o.optString("knd") == "p") AccountProfile.Kind.Personal else AccountProfile.Kind.Member,
                tenant = o.optString("ten").ifBlank { null }?.let { Tenancy(hex = it, name = "") },
                unlock = o.optString("unlock", "self")
            )
        }
    }

    private fun url(identity: String, path: String) = "${identity.trimEnd('/')}/$path"

    private fun envelope(response: Response, what: String): JSONObject {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) Failures.raise(response, body, what)
        return runCatching { JSONObject(body) }.getOrNull() ?: throw AdukiException.Network("Empty response from $what")
    }

    private fun data(response: Response, what: String): JSONObject =
        envelope(response, what).optJSONObject("data")
            ?: throw AdukiException.Network("Empty response from $what")

    private fun list(response: Response, what: String): JSONArray =
        envelope(response, what).optJSONArray("data")
            ?: throw AdukiException.Network("Empty response from $what")
}
