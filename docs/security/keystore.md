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
