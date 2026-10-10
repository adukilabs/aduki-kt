package pro.aduki.net.http

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Oidc is the relying-party client for "Sign in with Aduki" (ADK-KT-003 K7,
 * ADK-AUTH-001 §12.1): authorization code with PKCE `S256` (mandatory at
 * Aduki ID, there is no `plain`), the token endpoint, rotating refresh,
 * userinfo, revocation and id-token validation (`EdDSA`).
 *
 * The browser step is the caller's: open [Begin.url], and hand the redirect
 * URI it ends on to [finish]. [secret] selects `client_secret_basic` (default)
 * or `client_secret_post` ([post]); without a secret the client is public
 * (`none`, PKCE carries the proof). A [dpop] binds the tokens to a device key
 * (optional at Aduki ID).
 *
 * [issuer] is the Aduki ID origin, e.g. `https://id.aduki.pro`; the OAuth
 * paths come from discovery. [client] must carry no credentials.
 */
class Oidc(
    private val client: OkHttpClient,
    private val issuer: String,
    private val id: String,
    private val redirect: String,
    private val secret: String? = null,
    private val post: Boolean = false,
    private val dpop: Dpop? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    /** The endpoints Aduki ID publishes at `/.well-known/openid-configuration`. */
    class Discovery(
        val issuer: String,
        val authorize: String,
        val token: String,
        val userinfo: String,
        val jwks: String,
        val revocation: String?
    )

    /** An authorization request in flight: keep [verifier], [state] and [nonce] until the redirect. */
    class Begin(val url: String, val state: String, val nonce: String, val verifier: String)

    /** The result of a code exchange or a refresh. */
    class Grant(
        val access: String,
        val type: String,
        val expires: Long,
        val scope: String,
        val refresh: String?,
        /** The validated id token claims (absent on a refresh that returned none). */
        val claims: JSONObject?
    )

    private var found: Discovery? = null
    private var keys: JSONArray? = null

    /** Fetches and checks the discovery document (the issuer must be exactly this one). */
    fun discover(): Discovery {
        found?.let { return it }
        val body = get("${issuer.trimEnd('/')}/.well-known/openid-configuration")
        val json = JSONObject(body)
        if (json.optString("issuer") != issuer.trimEnd('/')) throw AdukiException.Protocol("Issuer mismatch in discovery")
        if (!json.optJSONArray("code_challenge_methods_supported").has("S256")) {
            throw AdukiException.Protocol("Provider does not offer PKCE S256")
        }
        return Discovery(
            issuer = json.getString("issuer"),
            authorize = json.getString("authorization_endpoint"),
            token = json.getString("token_endpoint"),
            userinfo = json.getString("userinfo_endpoint"),
            jwks = json.getString("jwks_uri"),
            revocation = json.optString("revocation_endpoint").ifBlank { null }
        ).also { found = it }
    }

    /**
     * Starts a sign-in: a fresh `state`, `nonce` and PKCE verifier, and the
     * authorization URL. [scope] must include `openid`; add `offline_access`
     * to get a refresh token.
     */
    fun begin(scope: List<String> = listOf("openid", "profile")): Begin {
        require("openid" in scope) { "scope must include openid" }
        val state = token(24)
        val nonce = token(24)
        val verifier = token(48) // 64 characters, within RFC 7636's 43..128
        val url = discover().authorize.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", id)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", scope.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
        return Begin(url.toString(), state, nonce, verifier)
    }

    /**
     * Completes a sign-in from the [callback] URI the browser ended on: checks
     * `error`, `state` and `iss` (RFC 9207), exchanges the code with the PKCE
     * verifier and validates the id token. The code is single use: a failed
     * exchange cannot be retried, start a new [begin].
     */
    fun finish(begin: Begin, callback: String): Grant {
        // The redirect may be a native app's custom scheme, which HttpUrl refuses.
        val query = (java.net.URI(callback).rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val at = it.indexOf('=')
            val key = if (at < 0) it else it.substring(0, at)
            val value = if (at < 0) "" else it.substring(at + 1)
            java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
        }
        query["error"]?.let { code ->
            val text = query["error_description"]
            if (code == "access_denied") throw AdukiException.Unauthorized("access_denied${text?.let { ": $it" }.orEmpty()}")
            throw AdukiException.Protocol("$code${text?.let { ": $it" }.orEmpty()}")
        }
        if (query["state"] != begin.state) throw AdukiException.Protocol("State mismatch")
        val iss = query["iss"]
        if (iss != null && iss != discover().issuer) throw AdukiException.Protocol("Issuer mismatch in redirect")
        val code = query["code"] ?: throw AdukiException.Protocol("No code in redirect")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("code_verifier", begin.verifier)
        val grant = grant(form, null)
        val raw = grant.second ?: throw AdukiException.Protocol("No id_token in token response")
        val claims = validate(raw, begin.nonce, grant.first.access)
        return Grant(grant.first.access, grant.first.type, grant.first.expires, grant.first.scope, grant.first.refresh, claims)
    }

    /**
     * Renews with a refresh token. The refresh token rotates: store the new
     * one, the old one is spent (presenting it again revokes the family). A
     * [scope] may only narrow.
     */
    fun refresh(refreshToken: String, scope: String? = null): Grant {
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken)
        if (scope != null) form.add("scope", scope)
        val result = grant(form, refreshToken)
        val claims = result.second?.let { validate(it, null, result.first.access) }
        val g = result.first
        return Grant(g.access, g.type, g.expires, g.scope, g.refresh, claims)
    }

    /** The userinfo claims for [grant]'s access token (sent with the `DPoP` scheme when bound). */
    fun userinfo(grant: Grant): JSONObject {
        val endpoint = discover().userinfo
        val bound = grant.type.equals("DPoP", ignoreCase = true)
        val builder = Request.Builder().url(endpoint)
            .header("Authorization", "${if (bound) "DPoP" else "Bearer"} ${grant.access}")
        if (bound && dpop == null) throw AdukiException.Protocol("Token is DPoP-bound but no DPoP key was given")
        val request = builder.build().let { if (bound) forceBound(it, grant.access) else it }
        return JSONObject(send(request))
    }

    /** Revokes [token] (a refresh token takes its family). Aduki ID answers 200 in every case. */
    fun revoke(token: String) {
        val endpoint = discover().revocation ?: throw AdukiException.Protocol("Provider has no revocation endpoint")
        val form = FormBody.Builder().add("token", token)
        send(authenticated(Request.Builder().url(endpoint), form).build())
    }

    // The DPoP interceptor binds only tokens whose cnf matches; the userinfo
    // token is opaque, so sign for it explicitly.
    private fun forceBound(request: Request, access: String): Request {
        val proof = dpop!!.proof(request.method, request.url.toString(), access)
        return request.newBuilder().header("DPoP", proof).build()
    }

    private class Raw(val access: String, val type: String, val expires: Long, val scope: String, val refresh: String?)

    private fun grant(form: FormBody.Builder, spending: String?): Pair<Raw, String?> {
        val builder = Request.Builder().url(discover().token)
        val request = authenticated(builder, form).let { it.build() }
        val withProof = if (dpop != null) request.newBuilder().header("DPoP", dpop.proof("POST", request.url.toString())).build() else request
        val json = JSONObject(send(withProof))
        // Read the rotated refresh token first: the old one is spent on a 2xx.
        val rotated = json.optString("refresh_token").ifBlank { null }
        val access = json.optString("access_token")
        if (access.isBlank()) {
            throw AdukiException.Auth("Missing access_token in token response", refresh = rotated ?: "")
        }
        val raw = Raw(
            access = access,
            type = json.optString("token_type", "Bearer"),
            expires = json.optLong("expires_in", 0),
            scope = json.optString("scope"),
            refresh = rotated
        )
        if (spending != null && rotated == null) {
            throw AdukiException.Auth("Missing refresh_token in refresh response")
        }
        return raw to json.optString("id_token").ifBlank { null }
    }

    private fun authenticated(builder: Request.Builder, form: FormBody.Builder): Request.Builder {
        when {
            secret == null -> form.add("client_id", id)
            post -> { form.add("client_id", id); form.add("client_secret", secret) }
            else -> builder.header("Authorization", Credentials.basic(id, secret))
        }
        return builder.post(form.build())
    }

    // OAuth endpoints answer {error, error_description}, not the REST envelope.
    private fun send(request: Request): String =
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return body
                val json = runCatching { JSONObject(body) }.getOrNull()
                val error = json?.optString("error").orEmpty()
                val text = json?.optString("error_description").orEmpty()
                val message = if (error.isBlank()) "HTTP ${response.code}" else "$error${if (text.isBlank()) "" else ": $text"}"
                when {
                    error == "invalid_grant" || error == "invalid_client" || error == "invalid_token" || response.code == 401 ->
                        throw AdukiException.Unauthorized(message)
                    else -> throw AdukiException.Network(message, code = response.code)
                }
            }
        } catch (e: IOException) {
            throw AdukiException.Network("Request failed: ${e.message}", e)
        }

    private fun get(url: String) = send(Request.Builder().url(url).build())

    /** Validates an `EdDSA` id token (OpenID Core §3.1.3.7) and returns its claims. */
    private fun validate(raw: String, nonce: String?, access: String): JSONObject {
        val parts = raw.split('.')
        if (parts.size != 3) throw AdukiException.Protocol("Malformed id_token")
        val header = JSONObject(String(dec(parts[0])))
        if (header.optString("alg") != "EdDSA") throw AdukiException.Protocol("Unexpected id_token algorithm")
        if (!verified(header.optString("kid"), "${parts[0]}.${parts[1]}", dec(parts[2]))) {
            throw AdukiException.Protocol("Bad id_token signature")
        }
        val claims = JSONObject(String(dec(parts[1])))
        val now = clock() / 1000
        val aud = claims.opt("aud")
        val audiences = if (aud is JSONArray) (0 until aud.length()).map { aud.getString(it) } else listOf(aud?.toString())
        when {
            claims.optString("iss") != discover().issuer -> throw AdukiException.Protocol("id_token issuer mismatch")
            id !in audiences -> throw AdukiException.Protocol("id_token audience mismatch")
            claims.has("azp") && claims.getString("azp") != id -> throw AdukiException.Protocol("id_token azp mismatch")
            claims.optLong("exp", 0) + SKEW < now -> throw AdukiException.Protocol("id_token expired")
            claims.optLong("iat", 0) - SKEW > now -> throw AdukiException.Protocol("id_token issued in the future")
            nonce != null && claims.optString("nonce") != nonce -> throw AdukiException.Protocol("id_token nonce mismatch")
            claims.optString("sub").isBlank() -> throw AdukiException.Protocol("id_token has no sub")
        }
        val atHash = claims.optString("at_hash")
        if (atHash.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(access.toByteArray())
            if (atHash != Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(16))) {
                throw AdukiException.Protocol("id_token at_hash mismatch")
            }
        }
        return claims
    }

    // Looks the key up by `kid`; an unknown one refetches the JWKS once (rotation).
    private fun verified(kid: String, input: String, signature: ByteArray): Boolean {
        for (attempt in 0..1) {
            if (keys == null || attempt == 1) keys = JSONObject(get(discover().jwks)).optJSONArray("keys") ?: JSONArray()
            val list = keys!!
            for (i in 0 until list.length()) {
                val jwk = list.getJSONObject(i)
                if (jwk.optString("kid") != kid || jwk.optString("kty") != "OKP" || jwk.optString("crv") != "Ed25519") continue
                val key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(PREFIX + dec(jwk.getString("x"))))
                return Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update(input.toByteArray())
                    runCatching { verify(signature) }.getOrDefault(false)
                }
            }
        }
        return false
    }

    private fun token(bytes: Int) = enc(ByteArray(bytes).also(random::nextBytes))
    private fun challenge(verifier: String) = enc(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private fun enc(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun dec(text: String) = Base64.getUrlDecoder().decode(text)

    private fun JSONArray?.has(value: String) = this != null && (0 until length()).any { optString(it) == value }

    private companion object {
        const val SKEW = 30L
        val PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    }
}
