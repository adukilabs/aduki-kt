# Envelope cipher

`pro.aduki.crypto.cipher.Envelope` is AES-256-GCM authenticated encryption.

```kotlin
object Envelope {
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray
    fun decrypt(cipherText: ByteArray, key: SecretKey): ByteArray
}
```

## Format

`encrypt` returns `IV (12 bytes) || ciphertext || tag (16 bytes)`. The IV is
the one the cipher generates (a fresh random 12 bytes on every call; the Android
Keystore refuses a caller-supplied IV, so the cipher is initialised without one
and `Cipher.getIV()` is stored). The layout is unchanged, so blobs written
earlier still decrypt. The transformation is
`AES/GCM/NoPadding` with a 128-bit tag.

`decrypt` requires at least 28 bytes (`IllegalArgumentException` otherwise) and
throws `javax.crypto.AEADBadTagException` when the data or tag was modified or
the key is wrong.

```kotlin
val key = Provider().get()
val sealed = Envelope.encrypt("secret".toByteArray(), key)
val plain = Envelope.decrypt(sealed, key)
```
