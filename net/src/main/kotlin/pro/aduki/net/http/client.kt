package pro.aduki.net.http

import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client factory for building high-performance OkHttpClient with connection pooling.
 */
object Client {

    fun create(
        key: String,
        timeout: Long = 15,
        pinner: CertificatePinner? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, TimeUnit.MINUTES))
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .addInterceptor(Auth(key))
            .retryOnConnectionFailure(true)

        if (pinner != null) {
            builder.certificatePinner(pinner)
        }

        return builder.build()
    }
}
