# Dual-Stack Network Transport Specification

This document details the network transport architecture of the Aduki Android Kotlin SDK. The SDK provides a dual-stack engine combining **gRPC (via `grpc-okhttp`)** for binary streaming and synchronization with **REST (via `OkHttp 4`)** for HTTP/2 multiplexed endpoints and multipart blob uploads.

---

## 1. Dual Transport Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                     Aduki Network Layer                    │
└──────────────┬──────────────────────────────┬───────────────┘
               │                              │
       (High-Throughput / Sync)        (REST / Blob Uploads)
               ▼                              ▼
┌──────────────────────────────┐┌─────────────────────────────┐
│          gRPC Stack          ││          REST Stack         │
│  (grpc-kotlin + grpc-okhttp) ││  (OkHttp 4 + HTTP/2 + Brotli│
└──────────────┬───────────────┘└─────────────┬───────────────┘
               │                              │
               └──────────────┬───────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                Connection & Security Pipeline               │
│     (TLS 1.3, SPKI Pinning, Key Interceptor, Circuit Breaker)│
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Aduki Server Fleet                     │
│        REST: https://hermers.aduki.pro/v1 (Port 443)        │
│        gRPC: grpc.aduki.pro (Port 443 TLS)                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. API Key Authentication & Whoami Resolution

All SDK requests authenticate using an API key in the format `hm_live_...`. Callers never supply raw tenant or user hexadecimal IDs; the SDK resolves and caches the authenticated identity on startup.

### 2.1. REST Auth Interceptor

```kotlin
package pro.aduki.net.http

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Key $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "Aduki-Android-SDK/1.0.0")
            .build()
        return chain.proceed(request)
    }
}
```

### 2.2. gRPC CallCredentials Interceptor

```kotlin
package pro.aduki.net.grpc

import io.grpc.CallCredentials
import io.grpc.Metadata
import java.util.concurrent.Executor

class KeyCallCredentials(private val apiKey: String) : CallCredentials() {
    private val keyMetadataKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: Executor,
        applier: MetadataApplier
    ) {
        appExecutor.execute {
            val headers = Metadata()
            headers.put(keyMetadataKey, "Key $apiKey")
            applier.apply(headers)
        }
    }

    override fun thisUsesUnstableApi() {}
}
```

---

## 3. OkHttp Engine Configuration

The REST client is tuned for mobile performance:

1. **HTTP/2 Multiplexing**: Multiple requests share a single underlying TLS socket, eliminating handshake latency.
2. **Aggressive Connection Pool**: Keeps connections alive for 5 minutes of idle time.
3. **Transparent Compression**: Supports Brotli and Gzip decompression.

```kotlin
package pro.aduki.net.http

import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    fun create(apiKey: String, pinner: CertificatePinner): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(apiKey))
            .certificatePinner(pinner)
            .retryOnConnectionFailure(true)
            .build()
    }
}
```

---

## 4. gRPC Mobile Channel Configuration

gRPC on Android utilizes `grpc-okhttp` instead of `grpc-netty` to avoid heavy native Netty dependencies and ensure optimal battery life on Android runtime (ART):

```kotlin
package pro.aduki.net.grpc

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit

object GrpcChannelFactory {

    fun create(host: String, port: Int, apiKey: String): ManagedChannel {
        return OkHttpChannelBuilder.forAddress(host, port)
            .useTransportSecurity() // Enforce TLS
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(false)
            .build()
    }
}
```

---

## 5. Mobile Circuit Breaker

Repeatedly failing network calls when a device travels through tunnels or loses signal drain mobile batteries. The SDK embeds an atomic state-machine circuit breaker:

- **CLOSED**: Normal operation.
- **OPEN**: Tripped after 5 consecutive transport failures. Subsequent requests fail fast immediately without turning on the device radio.
- **HALF-OPEN**: After 15 seconds, permits a single trial probe request to test network viability.
