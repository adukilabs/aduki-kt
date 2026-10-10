# DPoP (device-bound tokens)

DPoP (RFC 9449) binds a session to a device key: each request carries a
signed proof that only the holder of the key can make. Binding is optional at
Aduki ID.

```kotlin
val key = Software.p256()                 // pro.aduki.crypto.dpop.Software
val client = Aduki.login(handle, password, code = code, dpop = key)
// or
val client = Aduki.builder().token(token).dpop(key).build()
```

## Keys

`pro.aduki.crypto.dpop.Key` is a sign-only interface:

```kotlin
interface Key {
    val alg: String                     // "ES256" or "EdDSA"
    val jwk: Map<String, String>        // public members only, never `d`
    fun sign(input: ByteArray): ByteArray
    fun thumbprint(): String            // RFC 7638, stored by Aduki ID as cnf.jkt
}
```

`Software.p256()` (`ES256`) and `Software.ed25519()` (`EdDSA`) generate keys in
process memory using the JDK. Hardware keystores generate P-256 keys, so use
`ES256` for hardware-backed keys; a Keystore-backed `Key` is not part of the
SDK yet.

## What the SDK sends

`pro.aduki.net.http.Dpop` is an OkHttp interceptor installed by
`builder().dpop(key)` and `login(dpop = key)`:

- A request with no `Authorization` (sign-in, refresh) carries a proof with
  no access-token hash. Aduki ID binds the session to the key.
- A request whose access token is bound to this key (its `cnf.jkt` equals the
  key's thumbprint) is sent as `Authorization: DPoP <token>` with a proof
  carrying `ath`, the SHA-256 hash of the token.
- Other credentials (API keys, unbound tokens) are left unchanged.
- Each proof has `htm`, `htu` (without query or fragment), `iat`, a fresh
  random `jti`, and `nonce` when the server asked for one.
- A `DPoP-Nonce` from the server is remembered per host; a `401` or `400`
  carrying a new nonce is retried once with it.

The private key is never read; `Key` only signs.
