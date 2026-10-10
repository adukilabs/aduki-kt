# Static API Keys Reference

API keys give machine-to-machine access for background workers, tests and daemons, without interactive credentials.

---

## 1. Key format

The server (Aduki Mail) accepts `Authorization: Bearer <token>` or
`Authorization: Key <token>` for both Aduki ID tokens and API keys and tells
them apart by the shape of the token. The SDK therefore does not inspect the
token: a credential supplied through `Aduki.Builder.key(...)` is sent as
`Key <key>`, everything else (`token(...)`, a signed-in session, a refreshed
token) as `Bearer <token>`. Keys are issued and revoked in the Aduki admin
console; the SDK does not validate their format.

---

## 2. Builder Initialization

API keys are configured via `Aduki.Builder.key(String)`:

```kotlin
package pro.aduki.sdk

val client = Aduki.builder()
    .key(apiKey)
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

On all outbound HTTP requests, the SDK interceptor attaches the `Key` scheme header when the credential came from `key(...)`:

```http
GET /v1/user HTTP/1.1
Host: mail.aduki.pro
Authorization: Key <your key>
Accept: application/json
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
