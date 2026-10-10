package pro.aduki.net.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Auth interceptor attaching API key or Bearer JWT token to all HTTP requests.
 */
class Auth(private val supplier: () -> String) : Interceptor {

    constructor(key: String) : this({ key })

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = supplier().trim()
        val authHeader = when {
            token.isEmpty() -> ""
            token.startsWith("hm_") || token.startsWith("key_") -> "Key $token"
            else -> "Bearer $token"
        }

        val builder = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "Aduki-Android/1.0.0")

        if (authHeader.isNotEmpty()) {
            builder.header("Authorization", authHeader)
        }

        return chain.proceed(builder.build())
    }
}
