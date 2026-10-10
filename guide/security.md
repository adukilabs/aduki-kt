# Device-Level Security Specification

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win. gRPC was dropped (D-HOST-5, 2026-10-10): every mention of it below is history.

This document details the device-level security architecture of the Aduki Android Kotlin SDK. The security model enforces **hardware-backed isolation**, **zero unencrypted persistence**, and **deterministic memory sanitization**.

---

## 1. Security Architecture Principles

1. **Hardware-Enforced Cryptography**: Secrets are bound to dedicated secure hardware (StrongBox Keymaster chip or Trusted Execution Environment - TEE).
2. **Zero Plaintext at Rest** (a goal, not the current state): the local ObjectBox database is stored in the clear today; see section 4. Session tokens are held in memory only.
3. **In-Memory Zeroization**: Sensitive buffers (passwords, tokens, database keys) are stored in mutable arrays and zeroed immediately after use to protect against heap dump analysis.
4. **Transport Hardening**: Enforces TLS 1.3, strict SPKI certificate pinning, and disallows cleartext traffic.
5. **Biometric Crypto Binding**: Hardware keys can optionally require cryptographic biometric authentication (`BiometricPrompt`) for sensitive actions.

```text
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
└──────────────────────────────┬──────────────────────────────┘
                               │ Sensitive operation / Key use
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                Aduki Crypto Security Module                │
│   ┌───────────────────────────────────────────────────────┐ │
│   │ Memory Sanitizer (Zeroing mutable ByteArray/CharArray)│ │
│   └──────────────────────────┬────────────────────────────┘ │
└──────────────────────────────┼──────────────────────────────┘
                               │ Encrypt / Decrypt / Sign
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             Android KeyStore (Hardware Boundary)            │
│   ┌──────────────────────┐       ┌──────────────────────┐   │
│   │ StrongBox Keymaster  │  OR   │    ARM TrustZone     │   │
│   │ (Dedicated Hardware) │       │   (TEE Co-Processor) │   │
│   └──────────────────────┘       └──────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Hardware-Backed Android KeyStore Integration

Keys generated in the Android KeyStore are non-exportable; the private or secret key material never enters the Android OS application memory space.

### Key Generation Implementation

```kotlin
package pro.aduki.crypto.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object KeyStoreProvider {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_ALIAS = "aduki_master_key"

    fun getOrCreateMasterKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (keyStore.containsAlias(MASTER_ALIAS)) {
            return keyStore.getKey(MASTER_ALIAS, null) as SecretKey
        }

        val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (hasStrongBox) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }
}
```

---

## 3. In-Memory Sanitization (Memory Zeroing)

Standard Kotlin `String` objects are immutable and stored in the JVM heap, leaving sensitive credentials exposed to memory dumps until non-deterministic garbage collection occurs.

The Aduki SDK mandates **mutable arrays** for all sensitive data:

```kotlin
package pro.aduki.core.memory

import java.util.Arrays

inline fun <R> withWipedBytes(size: Int, block: (ByteArray) -> R): R {
    val buffer = ByteArray(size)
    try {
        return block(buffer)
    } finally {
        // Overwrite memory immediately with zeros
        Arrays.fill(buffer, 0.toByte())
    }
}

inline fun <R> withWipedChars(chars: CharArray, block: (CharArray) -> R): R {
    try {
        return block(chars)
    } finally {
        Arrays.fill(chars, '\u0000')
    }
}
```

---

## 4. Local database encryption: findings and design

Owner decision (2026-10-10): the local database must be encrypted with a key
held in the Android Keystore, and no document may say it is encrypted until it
has run on a device.

### 4.1 What ObjectBox 4.0.3 offers (checked 2026-10-10)

- `io.objectbox:objectbox-java:4.0.3`: `BoxStoreBuilder` has no encryption,
  key, password or cipher method (all 34 public members listed with `javap`);
  no class or string in the jar mentions encryption.
- `io.objectbox:objectbox-linux:4.0.3` (the native library the JVM tests load):
  no encryption-related string in the binary. A test
  (`FactoryTest.theDatabaseFileHoldsStoredTextInTheClear`) writes a marker and
  finds it in the clear in the database file.
- The Android artifact `objectbox-android` is not in the build cache and was
  not inspected; nothing public found suggests it differs. Public sources
  (GitHub issues `objectbox-java#8` and `#641`) describe the same situation:
  field encryption with `@Convert` is the suggested route. Not checked: whether
  a commercial ObjectBox edition offers at-rest encryption; ask the vendor
  before choosing a route.
- The old draft in this folder (`initialBytes(dbKey)`, "ObjectBox supports
  native AES-256-GCM encryption") was wrong: `initialBytes` loads initial
  data, it is not a key. `Factory.create(dir, key)` ignored its `key`; the
  parameter was removed.

### 4.2 Routes, and why none was built yet

| Route | Cost | State |
|---|---|---|
| Platform file-based encryption (Android FBE, on by default since Android 10) | none | protects a locked device only; not app-level, not Keystore-held by the SDK |
| Field encryption with `@Convert` (property converter to `ByteArray` using `Envelope`) | converters are no-arg classes, so the key must sit in a process-wide holder; encrypted properties cannot use `@Index` or substring queries (`Contact.name`/`email` are indexed and searched by `Contacts.search`, so search must move in memory or to a blind index); entity model and on-disk schema change; each entity needs migration | not small: touches every entity, `state` repositories and the model file; not built |
| Replace the store with an encrypted engine (SQLCipher-style) | rewrite of `store`, `state`, `sync` storage | not planned |

### 4.3 Design for field encryption (if chosen)

- Sensitive columns only: `Message.subject`, `preview`, `fromName`, `fromEmail`,
  `to`, `blob`; `Contact.name`, `email`, `phone`, `company`, `vcard`;
  `Appointment` text fields. Identifiers, flags, UIDs, timestamps stay in the
  clear so queries and sync keep working.
- Key: a random 256-bit data key, wrapped with `Envelope` under the Keystore
  master key (`Provider`, alias `aduki_master`) and stored in a small file next
  to the database; unwrapped at open into a process-wide holder used by the
  converters, wiped on close.
- Search over encrypted contact fields: in-memory filter after decrypting, or a
  keyed hash (blind index) for prefix search.
- JVM tests use a software key (`Provider` falls back to a process-lifetime key
  off Android, so a JVM test cannot prove survival across restarts; that needs
  a device or a file-backed software provider).
- Verification still to do on a device: the key is non-exportable in the
  Keystore, the database file holds no marker string, a wrong key fails to read,
  reinstall behaviour. Until then no document says "encrypted".

### 4.4 Envelope encryption sketch (the earlier draft, kept for the key wrapping)

```kotlin
package pro.aduki.crypto.cipher

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EnvelopeCipher {

    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    fun encrypt(plainBytes: ByteArray, masterKey: SecretKey): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val cipherText = cipher.doFinal(plainBytes)

        // Envelope: [12 bytes IV] + [Ciphertext + Tag]
        return iv + cipherText
    }

    fun decrypt(encryptedBytes: ByteArray, masterKey: SecretKey): ByteArray {
        val iv = encryptedBytes.copyOfRange(0, IV_LENGTH)
        val cipherText = encryptedBytes.copyOfRange(IV_LENGTH, encryptedBytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(cipherText)
    }
}
```

---

## 5. Transport Security & Certificate Pinning

Cleartext traffic is refused. Pinning is opt-in (decided 2026-10-10): the SDK
ships no pins because none was ever verified against a live host and a wrong
pin breaks every request. The app passes `Pin(host, hashes...)` to the builder
(`Options.pins`); `docs/security/tls.md` has the `openssl` command that prints
a hash. The earlier draft of this section listed pin values; they were
placeholders and are removed.

Needs the server: once `mail.` and `id.` answer on 443, take the SPKI hashes of
the live chain and of the backup key and record them here and in the app
configuration (see `AI/PROGRESS.md`, "Needs server").

## 6. Device Integrity & Root Detection

To protect sensitive emails against compromised operating systems, the SDK includes passive tamper and integrity hooks:

- **Play Integrity API Integration**: Generates hardware-attested integrity verdicts before granting access to enterprise mailboxes.
- **Debugger & Hook Detection**: Validates whether the application is running under active ptrace debugging (`Debug.isDebuggerConnected()`) or instrumentation injection (Frida/Xposed).
