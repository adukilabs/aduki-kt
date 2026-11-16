package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException

/** Server parameters and verification requirements for signup. */
data class Requirements(val phone: Boolean, val domain: String)

/**
 * Signup is where new Aduki accounts come from: the sign-up page of Aduki ID
 * in a browser. The SDK no longer creates accounts (1.0.0): apps open [url]
 * in a Custom Tab (Android: `pro.aduki.android.signin.SignUpTab`) and sign
 * in afterwards with `Aduki.signIn`. [requirements] stays for the personal
 * mail domain an app completes a handle with.
 */
object Signup {

    /** The path of the sign-up page on the Aduki ID site. */
    const val PATH = "/signup"

    /**
     * Discovers personal signup requirements from Aduki ID (`GET /v1/accounts`):
     * whether a phone is needed and the personal mail domain (`aduki.me`).
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
     * The address of the sign-up page (`https://id.aduki.pro/signup`) for
     * the Aduki ID at [identity] (its API base, `/v1` or not). [handle]
     * fills the address field. [back] is where the page sends the person
     * once the account is made: the app's own URI (a custom scheme such as
     * `pro.aduki.mail:/signup`, or an https App Link), which gets the new
     * account's handle as `?handle=` ([handle] reads it). Without [back]
     * the page ends on the site's own sign-in; closing the tab returns to
     * the app.
     */
    fun url(identity: String = Endpoints.ID, handle: String? = null, back: String? = null): String {
        val site = identity.trimEnd('/').removeSuffix("/v1").trimEnd('/')
        val query = buildList {
            handle?.trim()?.ifBlank { null }?.let { add("handle=${encode(it)}") }
            back?.let {
                val uri = runCatching { java.net.URI(it) }.getOrNull()
                require(uri != null && uri.scheme != null && uri.fragment == null) { "back is an absolute URI without a fragment" }
                add("return=${encode(it)}")
            }
        }
        return site + PATH + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }

    /**
     * The new account's handle when [uri] is the app's [back] address the
     * sign-up page returned to (scheme, authority and path equal), else
     * null. The handle is only a hint for the sign-in form: the person
     * still signs in with their password and code.
     */
    fun handle(uri: String, back: String): String? {
        val got = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
        val want = runCatching { java.net.URI(back) }.getOrNull() ?: return null
        if (!got.scheme.equals(want.scheme, ignoreCase = true)) return null
        if (!(got.rawAuthority ?: "").equals(want.rawAuthority ?: "", ignoreCase = true)) return null
        if ((got.rawPath ?: got.rawSchemeSpecificPart?.substringBefore('?')) != (want.rawPath ?: want.rawSchemeSpecificPart?.substringBefore('?'))) return null
        val query = got.rawQuery ?: return null
        return query.split('&').firstNotNullOfOrNull { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k == "handle") java.net.URLDecoder.decode(v, "UTF-8").trim().ifBlank { null } else null
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
