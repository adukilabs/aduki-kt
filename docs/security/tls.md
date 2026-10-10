# TLS & Certificate Pinning Reference

To eliminate man-in-the-middle (MITM) risks and defend against compromised certificate authority (CA) root stores, the Aduki Android SDK enforces certificate public key pinning and restricted forward-secret TLS 1.3 cipher suites.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.crypto.tls

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec

object Pinning {
    fun create(vararg pins: Pair<String, String>): CertificatePinner
    fun spec(): ConnectionSpec
}
```

---

## 2. SPKI Public Key Pinset

The SDK enforces SHA-256 Subject Public Key Info (SPKI) hashes:

```kotlin
val pinner = CertificatePinner.Builder()
    .add("*.aduki.pro", "sha256/k2oTQLGenANUdY3TR1Wd5rvUgUhysw+TGgcUJWZChd4=")
    .add("*.aduki.pro", "sha256/FEzVOUp4dF3gI0ZVPRJhFbS1c49mmG820f18LMMAmrA=") // Backup pin
    .build()
```

If a middlebox, proxy, or rogue CA intercepts the TLS handshake, OkHttp immediately aborts the connection with `javax.net.ssl.SSLPeerUnverifiedException` before any HTTP headers or auth tokens are transmitted.

---

## 3. Restricted Connection Spec & Forward Secrecy

The SDK configures `ConnectionSpec` to enforce modern, forward-secret cipher suites:

```kotlin
val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    .cipherSuites(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    )
    .build()
```

