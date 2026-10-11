# Keystore

`pro.aduki.crypto.keystore.Provider` creates and fetches AES-256 keys in the
Android Keystore.

```kotlin
class Provider(private val type: String = "AndroidKeyStore") {
    companion object { const val MASTER = "aduki_master" }

    fun get(alias: String = MASTER): SecretKey
    fun create(alias: String): SecretKey
    fun has(alias: String): Boolean
    fun remove(alias: String)
}
```

| Method | Does |
| :--- | :--- |
| `get` | returns the key under `alias`, creating it if missing |
| `create` | generates a new AES-256 key (`GCM`, no padding, encrypt and decrypt purposes) |
| `has` | whether the alias exists |
| `remove` | deletes the alias |

## Behaviour

- On Android the key is generated with `KeyGenParameterSpec` (reached by
  reflection, so the module also loads on a plain JVM). It does not ask for
  StrongBox; whether the key is hardware-backed depends on the device.
- Where the Android Keystore is not available (unit tests, a plain JVM), the
  provider falls back to an in-process software key cached by alias. Such a
  key does not survive the process, so data encrypted with it cannot be read
  after a restart.

```kotlin
val provider = Provider()
val key: SecretKey = provider.get(Provider.MASTER)
provider.remove(Provider.MASTER)   // e.g. on sign-out
```

## Device key (DPoP)

`pro.aduki.android.keystore.AndroidKey` (module `android`, see [Android module](../start/android.md)) is the P-256 signing key for [DPoP](../auth/dpop.md): generated in the Keystore, `PrivateKey.getEncoded()` is null, it signs `ES256`. `protection()` returns what the platform reports (`software`, `trusted_environment`, `strongbox`).

## Verified on

An Android 14 (API 34) x86_64 emulator, software CPU: `Provider` creates keys in the real `AndroidKeyStore` (not the fallback), `getEncoded()` is null, `KeyInfo.getKeySize()` is 256, and a caller-supplied IV is refused, which is why `Envelope` stores the IV the cipher generates (B14; 300 encryptions, no repeated IV). That emulator holds its keys in software (`isInsideSecureHardware` false): hardware backing and StrongBox are not verified.

`Provider.get` still falls back to a process-lifetime software key when the Keystore throws; on a device, check `has(alias)` after `get` if you must be sure a key is Keystore-held.
