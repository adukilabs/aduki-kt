# TLS and certificate pinning

`pro.aduki.crypto.tls.Pinning` holds the pinning and connection settings for
the production hosts.

```kotlin
object Pinning {
    const val REST_HOST = "mail.aduki.pro"
    const val GRPC_HOST = "grpc.aduki.pro"
    fun pinner(): CertificatePinner
    fun specs(): List<ConnectionSpec>
}
```

- `pinner()` returns an OkHttp `CertificatePinner` that pins the SPKI SHA-256
  hashes of `mail.aduki.pro` and `grpc.aduki.pro` (a primary and a backup pin).
  A mismatch aborts the handshake with `SSLPeerUnverifiedException` before any
  header is sent.
- `specs()` returns a `ConnectionSpec` restricted to TLS 1.3 and TLS 1.2.

## When the SDK applies it

The default HTTP client of `Aduki` installs `pinner()` when `secure(true)`
(the default) and the endpoint is not `localhost` or `127.0.0.1`. Pinning
applies to the hosts above only. `specs()` is available for your own client;
the SDK does not install it by itself.

A client you pass with `Builder.http(client)` is used as given; it does not
receive the pinner unless you add it.

Pins are tied to the server's certificate keys. If you point the SDK at your
own deployment, use `secure(false)` or supply your own client and pins.
