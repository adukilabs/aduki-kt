# Static API Keys Reference

API keys provide immutable machine-to-machine authentication for background workers, automated tests, headless kiosks, and daemons without requiring interactive user credentials.

---

## 1. Key Format & Specification

Aduki API keys are 64-character or 40-character cryptographic tokens formatted with an environment prefix:

| Prefix | Environment | Intended Use |
| :--- | :--- | :--- |
| `hm_live_<hex>` | Production | Live production tenants and real message dispatch |
| `hm_test_<hex>` | Sandbox / Staging | Integration testing, CI/CD pipelines, mock server testing |

### Examples

```text
hm_live_7f9b8c2d1e0a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a
hm_test_0123456789abcdef0123456789abcdef0123456789abcdef
```

---

## 2. Builder Initialization

API keys are configured via `Aduki.Builder.key(String)`:

```kotlin
package pro.aduki.sdk

val client = Aduki.builder()
    .key("hm_live_7f9b8c2d1e0a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a")
    .endpoint("https://hermers.aduki.pro/v1")
    .timeout(30)
    .secure(true)
    .build()
```

### Builder Method Signatures

```kotlin
fun Aduki.Builder.key(key: String): Aduki.Builder
```

- **`key: String`**: API key string. Must not be blank if no JWT token is provided.
- **Validation**: When `build()` is invoked, the builder enforces:
  ```kotlin
  require(apiKey.isNotBlank() || token.isNotBlank()) {
      "Either API key or JWT token must not be blank"
  }
  ```

---

## 3. Network Protocol Details

### REST HTTP Header

On all outbound HTTP requests, the SDK interceptor inspects the credential and attaches the `Key` scheme header:

```http
GET /v1/user HTTP/1.1
Host: hermers.aduki.pro
Authorization: Key hm_live_7f9b8c2d1e0a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a
Accept: application/json
```

### gRPC Transport Metadata

For gRPC channels, the SDK attaches the key as ASCII metadata in the `authorization` header:

```kotlin
val metadata = Metadata().apply {
    val key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    put(key, "Key $apiKey")
}
```

---

## 4. Scope Resolution

When initialized with an API key, calling `client.me()` contacts `GET /v1/user` to verify the key and retrieve its account and tenant:

```kotlin
val identity: Identity? = client.me()
if (identity != null) {
    println("Tenant: ${identity.tenant}")
    println("Tier: ${identity.tier}")
    println("Scopes: ${identity.scopes.joinToString()}")
}
```

### Permission Scopes

| Scope | Capability |
| :--- | :--- |
| `mail:read` | Inspect mailboxes, query CONDSTORE delta changes, fetch message metadata |
| `mail:send` | Enqueue outbound messages and dispatch raw RFC 822 blobs |
| `mail:modify` | Flag, move, or delete messages |
| `contacts:read` | Query address book contacts and incremental ctag changes |
| `contacts:write` | Create, update, or delete contacts |

