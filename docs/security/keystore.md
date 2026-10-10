# Android KeyStore Integration Reference

The `Provider` class (`crypto/keystore/provider.kt`) encapsulates cryptographic key lifecycle management using the Android KeyStore hardware root of trust.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.crypto.keystore

import javax.crypto.SecretKey

class Provider(private val strongbox: Boolean = true) {
    companion object {
        const val MASTER = "aduki_master"
    }

    fun get(alias: String = MASTER): SecretKey

    fun remove(alias: String = MASTER)

    fun clear()
}
```

### Parameters & Defaults

| Method | Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `get` | `alias` | `String` | `Provider.MASTER` | KeyStore unique entry alias. If missing, a new key is generated in hardware. |
| `remove` | `alias` | `String` | `Provider.MASTER` | Alias of entry to delete from hardware KeyStore. |
| `clear` | — | — | — | Deletes all SDK keys from the hardware vault. |

---

## 2. Hardware-Backed Generation Specs

When a key does not exist under the requested alias, the SDK invokes `KeyGenerator` with `KeyGenParameterSpec`:

```kotlin
val spec = KeyGenParameterSpec.Builder(
    alias,
    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    .apply {
        if (strongbox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setIsStrongBoxBacked(true)
        }
    }
    .build()
```

### Isolation Hierarchy
1. **StrongBox Keymaster (Android 9+)**: Physical tamper-resistant hardware security module (dedicated CPU, RAM, and flash).
2. **Trusted Execution Environment (TEE)**: Hardware-isolated ARM TrustZone processor enclave.
3. **Local JVM Fallback**: Software keystore utilized strictly during host JVM unit testing environments.

---

## 3. Usage Example

```kotlin
val provider = Provider()

// Retrieves existing 256-bit AES key or creates new one inside StrongBox
val masterKey: SecretKey = provider.get(Provider.MASTER)

// Wipe keys when user logs out
provider.remove(Provider.MASTER)
```

