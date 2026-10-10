package pro.aduki.net.grpc

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit

/**
 * Channel factory for building battery-efficient mobile gRPC channels using OkHttp transport.
 */
object Channel {

    fun create(host: String, port: Int = 443): ManagedChannel {
        return OkHttpChannelBuilder.forAddress(host, port)
            .useTransportSecurity()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(false)
            .build()
    }
}

