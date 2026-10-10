package pro.aduki.net.http

/**
 * The `Authorization` header value for a credential.
 *
 * Aduki Mail accepts `Bearer <token>` or `Key <token>` for both Aduki ID
 * access tokens and API keys and tells them apart by the shape of the token,
 * so the scheme is not significant to the server. The SDK still sends `Key`
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
}
