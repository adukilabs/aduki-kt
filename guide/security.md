# Device-Level Security Specification

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win. gRPC was dropped (D-HOST-5, 2026-10-10): every mention of it below is history.

This document details the device-level security architecture of the Aduki Android Kotlin SDK. The security model enforces **hardware-backed isolation**, **zero unencrypted persistence**, and **deterministic memory sanitization**.

---

## 1. Security Architecture Principles

1. **Hardware-Enforced Cryptography**: Secrets are bound to dedicated secure hardware (StrongBox Keymaster chip or Trusted Execution Environment - TEE).
2. **Zero Plaintext at Rest**: All local databases (ObjectBox), cached blobs, and session tokens are encrypted using AES-256-GCM.
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

## 4. Envelope Encryption for ObjectBox & Blobs

For file blobs (email attachments) and ObjectBox database encryption:

- A 256-bit AES data key is generated randomly.
- The data key is encrypted using the hardware-backed Master Key and stored in a protected envelope.
- At runtime, the data key is decrypted into a temporary byte buffer, loaded into ObjectBox native memory, and immediately wiped from JVM memory.

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
