# Configuration & Initialization Reference

The Aduki Android SDK is configured either interactively via `Aduki.login(...)` or fluently via `Aduki.builder()`.

---

## 1. Configuration Data Models

### `Options` Data Class

All network and runtime parameters are encapsulated in the immutable `Options` data class:

```kotlin
package pro.aduki.core.config

data class Options(
    val endpoint: String = Endpoints.REST,
    val grpcHost: String = Endpoints.GRPC_HOST,
    val grpcPort: Int = Endpoints.GRPC_PORT,
    val timeoutSeconds: Long = 15,
    val secure: Boolean = true,
    val maxRetries: Int = 3
)
```

### `Endpoints` Constant Object

```kotlin
package pro.aduki.core.config

object Endpoints {
    const val REST = "https://mail.aduki.pro/v1"
    const val GRPC_HOST = "grpc.aduki.pro"
    const val GRPC_PORT = 443
}
```

---

## 2. Builder Method Signatures

```kotlin
package pro.aduki.sdk

class Builder {
    fun key(key: String): Builder
    fun token(token: String): Builder
    fun endpoint(endpoint: String): Builder
    fun grpc(host: String, port: Int = Endpoints.GRPC_PORT): Builder
    fun secure(enabled: Boolean): Builder
    fun timeout(seconds: Long): Builder
    fun http(client: OkHttpClient): Builder
    fun manager(manager: Manager): Builder
    fun worker(worker: Worker): Builder
    fun mail(repo: MailRepo): Builder
    fun contacts(repo: ContactRepo): Builder
    fun engines(mailbox: MailboxEngine, contact: ContactEngine): Builder
    fun build(): Aduki
}
```

### Parameters & Defaults

| Method | Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `key` | `key` | `String` | `""` | Static API key (`hm_live_...` or `hm_test_...`). |
| `token` | `token` | `String` | `""` | Pre-existing JWT access token for user sessions. |
| `endpoint` | `endpoint` | `String` | `Endpoints.REST` | Base REST API URL. |
| `grpc` | `host`, `port`| `String`, `Int` | `grpc.aduki.pro`, `443` | Target gRPC host and TLS port. |
| `secure` | `enabled` | `Boolean` | `true` | Enables hardware KeyStore StrongBox envelope encryption for local databases. |
| `timeout` | `seconds` | `Long` | `15` | OkHttp socket connect, read, and write timeout in seconds. |
| `http` | `client` | `OkHttpClient` | `null` | Optional custom OkHttpClient instance. |
| `identity` | `identity` | `String` | `https://id.aduki.pro/v1` | Aduki ID base used to renew and revoke sign-ins. |

---

## 3. Initialization Examples

### Interactive Human Login
```kotlin
val client = Aduki.login(
    handle = "alice@aduki.me",
    password = "CorrectHorseBatteryStaple123!",
    code = "482019" // authenticator code; Aduki ID requires a second factor
)
```

### Headless Worker via API Key
```kotlin
val client = Aduki.builder()
    .key("hm_live_7f9b8c2d1e0a4b5c6d7e8f9a0b1c2d3e")
    .endpoint("https://mail.aduki.pro/v1")
    .grpc("grpc.aduki.pro", 443)
    .timeout(30)
    .secure(true)
    .build()
```

---

## 4. Dependency Injection (Hilt / Dagger)

```kotlin
package com.example.adukiapp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pro.aduki.sdk.Aduki
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AdukiModule {

    @Provides
    @Singleton
    fun provideAduki(): Aduki {
        return Aduki.builder()
            .key(BuildConfig.ADUKI_API_KEY)
            .endpoint(BuildConfig.ADUKI_ENDPOINT)
            .secure(true)
            .timeout(20)
            .build()
    }
}
```

