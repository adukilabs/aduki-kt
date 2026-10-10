# Dual Network Transports Reference

The Aduki Android SDK implements a dual-stack transport layer: an **OkHttp 4.12.0 HTTP/2 REST client** for lightweight CRUD and metadata operations, and an **OkHttp-backed gRPC Protobuf pipeline** for high-volume delta streaming.

---

## 1. OkHttp 4 HTTP/2 REST Transport

### Configuration & Factory Signature

```kotlin
package pro.aduki.net.http

object Client {
    fun create(
        auth: String = "",
        timeoutSeconds: Long = 15,
        tokenSupplier: (() -> String?)? = null
    ): OkHttpClient
}
```

### Connection Pooling & Socket Configuration
- **Multiplexing**: HTTP/2 over single TLS 1.3 socket, eliminating TCP 3-way handshake roundtrips.
- **Connection Pool**: 5 idle sockets retained with a 5-minute keepalive window (`ConnectionPool(5, 5, TimeUnit.MINUTES)`).
- **Timeouts**:
  - Connect Timeout: configurable (default 15 seconds).
  - Read Timeout: configurable (default 15 seconds).
  - Write Timeout: configurable (default 15 seconds).
- **DNS & Happy Eyeballs**: Parallel IPv4 / IPv6 resolution with RFC 8305 connection fallback.

### Dual-Mode `Auth` Interceptor

The internal `Auth` interceptor inspects the credential on every outbound request:

```kotlin
class Auth(
    private val staticAuth: String,
    private val tokenSupplier: (() -> String?)? = null
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val currentToken = tokenSupplier?.invoke() ?: staticAuth
        val scheme = if (currentToken.startsWith("hm_")) "Key" else "Bearer"
        val request = chain.request().newBuilder()
            .header("Authorization", "$scheme $currentToken")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}
```

---

## 2. gRPC Protobuf OkHttp Channel Pipeline

For streaming mailbox deltas and heavy binary payload synchronization, Aduki utilizes `grpc-okhttp`:

### Channel Factory Signature

```kotlin
package pro.aduki.net.grpc

object Channel {
    fun build(
        host: String = Endpoints.GRPC_HOST,
        port: Int = Endpoints.GRPC_PORT,
        secure: Boolean = true
    ): ManagedChannel
}
```

### gRPC Call Credentials & Metadata

Every RPC call receives authorization metadata without incurring socket renegotiation:

```kotlin
package pro.aduki.net.grpc

object MetadataFactory {
    fun create(token: String): io.grpc.Metadata {
        return io.grpc.Metadata().apply {
            val key = io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER)
            val scheme = if (token.startsWith("hm_")) "Key" else "Bearer"
            put(key, "$scheme $token")
        }
    }
}
```

---

## 3. Mandatory Protocol Headers

| Header | Value / Format | Purpose |
| :--- | :--- | :--- |
| `Authorization` | `Bearer <jwt>` or `Key <apiKey>` | Cryptographic identity and permission verification. |
| `X-Aduki-Idempotency-Key` | UUIDv4 (e.g., `7b9f8a02-1c3d-...`) | Prevents duplicate actions (sends, moves) upon network retries. |
| `Accept` | `application/json` | REST content negotiation. |
| `Content-Type` | `application/json; charset=utf-8` | JSON request body payload typing. |
| `User-Agent` | `Aduki-Android/1.0.0 (Linux; Android 14; Pixel 8)` | Client telemetry and version validation. |

