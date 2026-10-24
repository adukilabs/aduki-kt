package pro.aduki.net.http

/**
 * The `Authorization` header value for a credential.
 *
 * Aduki Mail accepts `Bearer <token>` or `Key <token>` for both Aduki ID
 * access tokens and API keys and tells them apart by the shape of the token,
 * so between those two the scheme is not significant. `DPoP` is: a token
 * bound to a device key (`Builder.dpop`) is sent as `DPoP <token>` with a
 * proof, which only mail servers with DPoP support accept. The SDK sends `Key`
 * for a credential the caller supplied as an API key (the builder's `key()`
 * path, tracked by [apiKey]) and `Bearer` for everything else. It never
 * guesses from a token prefix.
 */
object Scheme {

    /** The header value, or an empty string when [token] is blank. */
    fun header(token: String, apiKey: Boolean = false): String {
        val value = token.trim()
        if (value.isEmpty()) return ""
        return if (apiKey) "Key $value" else "Bearer $value"
    }

    /**
     * The message for a refused [request]. A device-bound (`DPoP`) token
     * refused as `auth.missing` comes from a mail server that predates DPoP
     * on REST and JMAP: say so, instead of a bare "credentials required".
     */
    fun refusal(request: okhttp3.Request, why: String): String {
        val bound = request.header("Authorization").orEmpty().startsWith("DPoP ")
        return if (bound && "auth.missing" in why) {
            "Unauthorized access ($why): this mail server does not accept device-bound (DPoP) tokens; sign in without a device key"
        } else {
            "Unauthorized access ($why)"
        }
    }
}
