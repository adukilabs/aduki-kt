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
fresh from `SecureRandom` on every call. The transformation is
`AES/GCM/NoPadding` with a 128-bit tag.

`decrypt` requires at least 28 bytes (`IllegalArgumentException` otherwise) and
throws `javax.crypto.AEADBadTagException` when the data or tag was modified or
the key is wrong.

```kotlin
val key = Provider().get()
val sealed = Envelope.encrypt("secret".toByteArray(), key)
val plain = Envelope.decrypt(sealed, key)
```
