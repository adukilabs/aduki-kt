# Static API Keys Reference

API keys give machine-to-machine access for background workers, tests and daemons, without interactive credentials.

---

## 1. Key format

The SDK recognises a credential as an API key when it starts with `hm_` or
`key_`, and then sends it as `Authorization: Key <key>`. Anything else is sent
as `Authorization: Bearer <token>`. Keys are issued and revoked in the Aduki
admin console; the SDK does not validate their format beyond this prefix test.

---

## 2. Builder Initialization

API keys are configured via `Aduki.Builder.key(String)`:

```kotlin
package pro.aduki.sdk

val client = Aduki.builder()
    .key(apiKey) // starts with hm_ or key_
    .endpoint("https://mail.aduki.pro/v1")
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
Host: mail.aduki.pro
Authorization: Key <your key>
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

## 4. Resolving the account

With an API key, `client.me()` calls `GET /v1/user` to verify the key and read its account and tenant:

```kotlin
val identity: Identity? = client.me()
println("User: ${identity?.user}, tenant: ${identity?.tenant}")
```

`Identity.scopes` and `Identity.tier` are filled only when the server returns
them; do not rely on them. API keys are never renewed.
