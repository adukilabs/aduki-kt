# Memory Sanitization Reference

To defend against heap memory dumping and side-channel memory inspection, the Aduki Android SDK provides zeroization utilities that overwrite sensitive buffers (`Arrays.fill(0)`) immediately after consumption.

---

## 1. Class & Function Signatures

```kotlin
package pro.aduki.core.memory

/**
 * Overwrites all bytes in the array with zeros.
 */
fun wipe(bytes: ByteArray)

/**
 * Overwrites all characters in the array with null characters ('\u0000').
 */
fun wipe(chars: CharArray)

/**
 * Scopes execution of a ByteArray, guaranteeing zeroization upon block completion.
 */
inline fun <R> withWipedBytes(bytes: ByteArray, block: (ByteArray) -> R): R

/**
 * Scopes execution of a CharArray, guaranteeing zeroization upon block completion.
 */
inline fun <R> withWipedChars(chars: CharArray, block: (CharArray) -> R): R
```

```kotlin
package pro.aduki.crypto.sanitizer

/**
 * AutoCloseable container that scrubs sensitive buffers upon close().
 */
class Guard<T>(val target: T) : AutoCloseable {
    override fun close()
}
```

---

## 2. Functional Scrubber Usage

```kotlin
val derivedKey = withWipedChars(password.toCharArray()) { chars ->
    // Key derivation executes with cleartext chars
    pbkdf2(chars, salt)
}
// Outside the block, chars is guaranteed filled with '\u0000'
```

---

## 3. AutoCloseable `Guard` Pattern

When passing sensitive memory buffers across multiple asynchronous or synchronous processing stages:

```kotlin
val sensitiveBytes = retrieveSecretKeyBytes()

Guard(sensitiveBytes).use { guard ->
    // The wrapped buffer is accessible via guard.target
    val hash = computeHmac(guard.target, message)
    sendVerification(hash)
}

// Immediately upon exiting the use block, sensitiveBytes contains all zeros
```

