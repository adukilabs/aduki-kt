# Configuration & Initialization Reference

The Aduki Android SDK is configured either interactively via `Aduki.login(...)` or fluently via `Aduki.builder()`.

---

## 1. Configuration Data Models

### `Options` Data Class

Network and runtime parameters are held in the immutable `Options` data class:

```kotlin
package pro.aduki.core.config

data class Options(
    val endpoint: String = Endpoints.REST,
    val identity: String = Endpoints.ID,
    val timeoutSeconds: Long = 15,
    val secure: Boolean = true,
    val pins: List<Pin> = emptyList() // TLS pins; none by default
)
```

### `Endpoints` Constant Object

```kotlin
package pro.aduki.core.config

object Endpoints {
    const val REST = "https://mail.aduki.pro/v1"
    const val ID = "https://id.aduki.pro/v1"
    const val AUDIENCE = "mail"
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
    fun identity(identity: String): Builder
    fun dpop(key: pro.aduki.crypto.dpop.Key): Builder
    fun secure(enabled: Boolean): Builder
    fun pins(vararg pins: Pin): Builder
    fun timeout(seconds: Long): Builder
    fun http(client: OkHttpClient): Builder
    fun manager(manager: Manager): Builder
    fun worker(worker: Worker): Builder
    fun mail(repo: MailRepo): Builder
    fun contacts(repo: ContactRepo): Builder
    fun scheduling(repo: AppointmentRepo, engine: ScheduleEngine? = null): Builder
    fun engines(mailbox: MailboxEngine, contact: ContactEngine): Builder
    fun build(): Aduki
}
```

### Parameters & Defaults

| Method | Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `key` | `key` | `String` | `""` | API key, sent as `Authorization: Key <key>` (only on this path; every other credential is `Bearer`) (see [API keys](../auth/keys.md)). |
| `token` | `token` | `String` | `""` | Pre-existing JWT access token for user sessions. |
| `endpoint` | `endpoint` | `String` | `Endpoints.REST` | Base REST API URL. |
| `secure` | `enabled` | `Boolean` | `true` | Allows TLS pinning on the default HTTP client when pins are configured. It does not pin anything by itself and does not encrypt local storage. |
| `pins` | `pins` | `Pin...` | none | Opt-in TLS pins per host ([TLS](../security/tls.md)); the SDK ships no pins. |
| `timeout` | `seconds` | `Long` | `15` | OkHttp socket connect, read, and write timeout in seconds. |
| `http` | `client` | `OkHttpClient` | `null` | Optional custom OkHttpClient instance. |
| `identity` | `identity` | `String` | `https://id.aduki.pro/v1` | Aduki ID base used to renew and revoke sign-ins. |
| `dpop` | `key` | `Key` | none | Device key; binds sign-ins to it ([DPoP](../auth/dpop.md)). |

---

## 3. Initialization Examples

### Interactive Human Login
```kotlin
val client = Aduki.login(
    handle = "alice@aduki.me",
    password = password,
    code = "482019" // authenticator code; Aduki ID requires a second factor
)
```

### Headless Worker via API Key
```kotlin
val client = Aduki.builder()
    .key(apiKey) // sent as Authorization: Key
    .endpoint("https://mail.aduki.pro/v1")
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

