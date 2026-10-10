# TLS and certificate pinning

Pinning is **opt-in**. The SDK ships no pins and no default pinned host: with
no configuration the default HTTP client uses the platform trust store only.
`pro.aduki.crypto.tls.Pinning` turns the pins you configure into an OkHttp
`CertificatePinner`.

```kotlin
object Pinning {
    fun pinner(pins: List<Pin>): CertificatePinner?   // null when pins is empty
    fun specs(): List<ConnectionSpec>
}
```

```kotlin
val client = Aduki.builder()
    .key(apiKey)
    .pins(
        Pin("mail.aduki.pro", "sha256/<current key hash>", "sha256/<backup key hash>"),
        Pin("id.aduki.pro", "sha256/<current key hash>", "sha256/<backup key hash>"),
    )
    .build()
```

- A `Pin` is a host and one or more `sha256/<base64>` hashes of the
  certificate's SubjectPublicKeyInfo. Give a current and a backup hash so a key
  rotation does not lock your app out. A malformed hash is rejected when the
  `Pin` is created.
- A mismatch aborts the handshake with `SSLPeerUnverifiedException` before any
  header is sent.
- `specs()` returns a `ConnectionSpec` restricted to TLS 1.3 and TLS 1.2. The
  SDK does not install it by itself.

## Getting a hash

Take the hash from the live certificate chain of the host, never from a
document:

```sh
openssl s_client -connect mail.aduki.pro:443 -servername mail.aduki.pro </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | base64
```

Prefix the output with `sha256/`. Repeat for the key you will rotate to (the
backup). This pins the leaf key; a CA-issued certificate that renews with a new
key will break pinned clients, so pin a key you control and keep a backup.

## When the SDK applies it

The default HTTP client of `Aduki` (and its Aduki ID client) installs the
pinner when pins are configured and `secure(true)` (the default). A client you
pass with `Builder.http(client)` is used as given; add your own
`certificatePinner`. `Aduki.login(..., pins = ...)` accepts the same pins for the
sign-in call.

## What is verified

The unit tests check the pinner against a local TLS server (MockWebServer with
a generated certificate): the matching pin connects, a wrong pin aborts the
handshake, a pin for another host does not interfere. No pin has been checked
against a production host: those hosts were not reachable on 443 when this was
written, so the SDK defines none.
