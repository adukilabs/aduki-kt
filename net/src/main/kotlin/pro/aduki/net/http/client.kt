package pro.aduki.net.http

import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client factory for the SDK's OkHttp clients.
 *
 * Every client it builds derives from one process-wide [shared] client, so
 * they share its connection pool and dispatcher: a second `Aduki` client
 * (another account, the one inside a sign-in) or the Aduki ID client reuses
 * open TLS connections and threads instead of starting its own.
 */
object Client {

    /** The process-wide base: one connection pool (8 idle connections, kept 5 minutes) and one dispatcher. */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    fun create(
        key: String,
        timeout: Long = 15,
        pinner: CertificatePinner? = null,
        apiKey: Boolean = false
    ): OkHttpClient {
        val builder = shared.newBuilder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .addInterceptor(Auth(key, apiKey))

        if (pinner != null) {
            builder.certificatePinner(pinner)
        }

        return builder.build()
    }
}
