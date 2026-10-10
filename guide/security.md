# Device-Level Security Specification

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win. gRPC was dropped (D-HOST-5, 2026-10-10): every mention of it below is history.

This document details the device-level security architecture of the Aduki Android Kotlin SDK. The security model enforces **hardware-backed isolation**, **zero unencrypted persistence**, and **deterministic memory sanitization**.

---

## 1. Security Architecture Principles

1. **Hardware-Enforced Cryptography**: Secrets are bound to dedicated secure hardware (StrongBox Keymaster chip or Trusted Execution Environment - TEE).
2. **Sealed payload columns** (not full-database encryption): message previews and bodies refs, contact vCards, outbox payloads and appointment notes are AES-256-GCM sealed; indexed metadata is in the clear; see section 4. Session tokens are held in memory only. Device-unverified.
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

### 4.2 Decision and what is built (owner, 2026-10-10)

"Platform encryption + encrypt sensitive fields": the file as a whole relies on
Android file-based encryption and OS protection; the sensitive payload columns
are sealed by the SDK. Built and tested on the JVM (software `Provider`):

- `crypto.cipher.Vault`: AES-256-GCM (`Envelope`) under keys from `Provider`.
  Sealed layout `0xAD 0x4B | version | key id (4 bytes) | IV | ciphertext | tag`;
  text is stored as `aduki:1:` + base64. Key id `n` is Provider alias
  `aduki_data_n`; `rotate()` makes a new key current, older values still open
  while their key exists, and any rewrite seals under the current key. Anything
  not in the sealed format is legacy plaintext and reads unchanged; a tampered
  value, an unknown key id or version throws `SealException` (fails closed).
- `store.box.Sealing`: the process-wide vault and the converters
  (`SealedText`, an ObjectBox `@Convert` on String columns). Sealed columns:
  `Message.preview`, `Message.blob`, `Contact.vcard`, `Contact.company`,
  `Appointment.notes`, and `Outbox.payload`. The outbox payload is sealed by
  the storage (`Sealing.put` / `Sealing.opened`) because the ObjectBox
  generator (4.0.3, kapt) emits invalid Java for a converted byte-array column.
  `Sealing.reseal(store)` rewrites every row (legacy plaintext and old keys
  upgrade to the current key).
- Left in the clear on purpose, so queries and sync work: names, subjects,
  e-mail addresses, phones, from/to, hexes, uids, flags, timestamps, thread ids,
  appointment times and status, every `@Index` column. These rely on OS
  protection only.
- Wiring: `Aduki.builder().secureStore(provider)`, or `Factory.build(dir, vault)`;
  on Android the Keystore `Provider` is the default when `secureStore` is not
  called (`Vault.platform()`); on a plain JVM nothing is sealed unless asked.
  With no vault installed values are written in clear and sealed values cannot
  be read.
- Cost of "legacy readable": a sealed-looking value written by something else is
  not told apart from ours except by the magic header or the `aduki:1:` prefix.
- LMDB copy-on-write can leave old (plaintext) pages in the file after a row is
  rewritten; `reseal` does not scrub them. A fresh database sealed from the
  start has none; a migrated one may, until the file is compacted.

Unverified until a device run: that the Android Keystore generates and uses
the key (the default Keystore spec refuses a caller-supplied IV, so `Provider`
now sets `setRandomizedEncryptionRequired(false)` via reflection; untested),
non-exportability, reinstall and backup behaviour, behaviour with a real
`objectbox-android` artifact. On a plain JVM a `Provider` key lives only for the
process, so JVM tests cannot show data surviving a restart.

### 4.3 Routes considered

| Route | State |
|---|---|
| Platform file-based encryption (Android FBE) | relied on for the rest of the file |
| Field encryption of sensitive payload columns | built (4.2) |
| Encrypting indexed or searched columns (names, subjects, addresses) | not done: it breaks queries; would need in-memory filtering or a blind index |
| Replace the store with an encrypted engine (SQLCipher-style) | not planned |

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
