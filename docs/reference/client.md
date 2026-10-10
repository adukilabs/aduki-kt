# Aduki API Reference

`Aduki` is the central facade entry point for all mobile operations in the Aduki Android Kotlin SDK.

---

## 1. Class Signature & Properties

```kotlin
package pro.aduki.sdk

class Aduki internal constructor(
    val apiKey: String = "",
    val token: String = "",
    val options: Options,
    val session: Session = Session(),
    val lifecycle: Lifecycle = Lifecycle(),
    private val httpClient: OkHttpClient? = null,
    manager: Manager? = null,
    worker: Worker? = null,
    mailRepo: MailRepo? = null,
    contactRepo: ContactRepo? = null,
    mailboxEngine: MailboxEngine? = null,
    contactEngine: ContactEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
)
```

### Public Member Properties

| Property | Type | Access | Description |
| :--- | :--- | :--- | :--- |
| `apiKey` | `String` | `val` | Static API key string (if initialized via API key). |
| `token` | `String` | `val` | Initial JWT access token string (if initialized via token). |
| `options` | `Options` | `val` | Immutable client network and endpoint options. |
| `session` | `Session` | `val` | Reactive state repository for active tokens and resolved user identity. |
| `lifecycle` | `Lifecycle` | `val` | Application foreground/background lifecycle coordinator. |
| `mail` | `Mail` | `val` | Sub-service for message sending, flag updates, moves, and mailbox observations. |
| `contacts` | `Contacts` | `val` | Sub-service for address book synchronization, contact retrieval, and substring search. |
| `sync` | `Sync` | `val` | Sub-service for CONDSTORE/MODSEQ folder delta sync and outbox flushing. |

---

## 2. Factory & Builder Methods

### `Aduki.Companion.login`
Signs in at Aduki ID for a mail token. See [Sign-in](../auth/login.md).

```kotlin
suspend fun Aduki.Companion.login(
    handle: String,
    password: String,
    code: String? = null,
    endpoint: String = Endpoints.REST,
    identity: String = Endpoints.ID,
    backup: String? = null
): Aduki
```

- **Parameters**:
  - `handle`: Full address, e.g. `ada@aduki.me`.
  - `password`: Account password.
  - `code` / `backup`: Authenticator code or backup code.
  - `endpoint`: Mail REST base (defaults to `https://mail.aduki.pro/v1`).
  - `identity`: Aduki ID base (defaults to `https://id.aduki.pro/v1`).
- **Return Type**: `Aduki` — holding the access token, refresh token and session, with `Identity` resolved.
- **Throws**: `AdukiException.Unauthorized` on a wrong password or second factor; `AdukiException.Network` otherwise.

---

### `Aduki.Companion.builder`
Creates a fluent `Builder` instance for custom client configuration.

```kotlin
fun Aduki.Companion.builder(): Aduki.Builder
```

#### `Builder` Methods

```kotlin
class Builder {
    fun key(key: String): Builder
    fun token(token: String): Builder
    fun endpoint(endpoint: String): Builder
    fun identity(identity: String): Builder
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

- **Validation in `build()`**: Enforces that at least one of `apiKey` or `token` is non-blank (`require(apiKey.isNotBlank() || token.isNotBlank())`).

---

## 3. Session & Lifecycle Methods

### `me`
Resolves and returns the authenticated user and tenant identity profile.

```kotlin
suspend fun me(): Identity?
```

- **Return Type**: `Identity?` — User ID hex, tenant hex, owner status, scopes, and tier. Returns cached instance from `session.identity.value` if already resolved; otherwise queries `GET /v1/user`. Scopes and tier are not part of that response and stay empty.

### `totp`
**Deprecated.** Second factors are managed in Aduki ID's Account Center; mail's `/v1/user/totp` is going away.

```kotlin
suspend fun totp(code: String): Boolean
```

- **Parameters**: `code: String` — Exactly 6 numeric digits.
- **Return Type**: `Boolean` — `true` if server confirms verification.
- **Throws**: `IllegalArgumentException` if code is not 6 digits; `AdukiException.Unauthorized` if session expired.

### `refresh`
Renews the access token at Aduki ID (`POST /v1/tokens`) ahead of time. The SDK also does this by itself on a `401`.

```kotlin
suspend fun refresh(): Boolean
```

- **Return Type**: `Boolean` — `true` if tokens were successfully rotated and updated in session state.

### `logout`
Terminates the session remotely and wipes local credentials.

```kotlin
suspend fun logout(): Boolean
```

- **Return Type**: `Boolean` — `true` if Aduki ID revoked the session (`DELETE /v1/sessions/{hex}`). Guarantees `session.clear()` executes locally.

### `pause`
Notifies the SDK that the host application entered the background. Suspends background polling.

```kotlin
fun pause()
```

### `resume`
Notifies the SDK that the host application entered the foreground. Resumes background polling and flushes pending outbox mutations.

```kotlin
fun resume()
```

---

## 4. Complete Sub-Services Index

### `client.mail` (`Mail`)
- `suspend fun send(to: List<String>, subject: String, body: String, mailbox: String = "outbox"): Message`
- `suspend fun flag(hex: String, flag: Int)`
- `suspend fun move(hex: String, dest: String)`
- `suspend fun remove(hex: String)`
- `fun observe(mailboxHex: String): StateFlow<List<Message>>?`
- `fun mailboxes(): StateFlow<List<Mailbox>>?`
- `fun unread(mailboxHex: String): StateFlow<Int>?`

### `client.contacts` (`Contacts`)
- `suspend fun sync(tenant: String = ""): Boolean`
- `fun observe(): StateFlow<List<Contact>>?`
- `fun search(query: String): StateFlow<List<Contact>>?`
- `fun get(hex: String): Contact?`

### `client.sync` (`Sync`)
- `suspend fun all(mailboxes: List<String> = listOf("inbox")): Boolean`
- `suspend fun flush(): Int`
- `fun pending(): Int`

