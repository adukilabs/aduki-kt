# Envelope Cipher Reference

The `Envelope` cipher (`crypto/cipher/envelope.kt`) provides authenticated encryption with associated data (AEAD) using AES-256-GCM.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.crypto.cipher

import javax.crypto.SecretKey

class Envelope(private val key: SecretKey) {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}
```

### Parameters & Return Values

| Method | Parameter | Type | Return Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| `encrypt` | `plaintext` | `ByteArray` | `ByteArray` | Encrypts raw bytes, prefixing 1-byte version and 12-byte IV, appending 16-byte GCM auth tag. |
| `decrypt` | `ciphertext` | `ByteArray` | `ByteArray` | Verifies tag authenticity and decrypts ciphertext into original plaintext. |

---

## 2. Cryptographic Specifications

- **Transformation**: `AES/GCM/NoPadding`
- **Key Length**: 256 bits (32 bytes)
- **Initialization Vector (IV)**: 96 bits (12 bytes), generated freshly with `SecureRandom` on each `encrypt` call.
- **Authentication Tag**: 128 bits (16 bytes), verifying ciphertext authenticity and preventing tampering.

---

## 3. Serialized Binary Wire Format

Payloads are packed into a compact binary format:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Version (1)  |                 IV (Bytes 0..2)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         IV (Bytes 3..6)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         IV (Bytes 7..10)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  IV (Byte 11) |           Encrypted Payload ...               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| ... Payload   |            Authentication Tag (16 Bytes)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## 4. Tamper Resistance & Exceptions

Modifying even a single bit of the encrypted payload or authentication tag triggers an immediate `javax.crypto.AEADBadTagException`:

```kotlin
val cipher = Envelope(masterKey)

val encrypted = cipher.encrypt("secret payload".toByteArray())

try {
    val decrypted = cipher.decrypt(encrypted)
    println(String(decrypted))
} catch (e: javax.crypto.AEADBadTagException) {
    // Cryptographic authentication failure — data has been tampered with or corrupted
    Log.e("Aduki", "Tamper detected: ${e.message}")
}
```

