package pro.aduki.net.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Auth interceptor attaching an API key (`Key`, only when [apiKey] is set) or a Bearer token to all HTTP requests.
 */
class Auth(private val apiKey: Boolean = false, private val supplier: () -> String) : Interceptor {

    constructor(key: String, apiKey: Boolean = false) : this(apiKey, { key })

    override fun intercept(chain: Interceptor.Chain): Response {
        val authHeader = Scheme.header(supplier(), apiKey)

        val builder = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "Aduki-Android/1.0.0")

        if (authHeader.isNotEmpty()) {
            builder.header("Authorization", authHeader)
        }

        return chain.proceed(builder.build())
    }
}
