# Memory sanitization

Helpers that overwrite sensitive buffers once they are no longer needed. This
reduces how long a secret sits in memory; the JVM may still have copied it, so
treat it as hygiene rather than a guarantee.

## `wipe`

```kotlin
package pro.aduki.core.memory

fun ByteArray.wipe()
fun CharArray.wipe()
fun IntArray.wipe()
fun LongArray.wipe()

inline fun <R> withWipedBytes(size: Int, block: (ByteArray) -> R): R   // allocates, wipes afterwards
inline fun <R> withWipedChars(chars: CharArray, block: (CharArray) -> R): R
```

```kotlin
val derived = withWipedChars(password.toCharArray()) { chars -> deriveKey(chars) }
// `chars` is zeroed here
```

## `Guard`

```kotlin
package pro.aduki.crypto.sanitizer

class Guard(val bytes: ByteArray) : Closeable        // close() wipes `bytes`
inline fun <R> withGuard(bytes: ByteArray, block: (Guard) -> R): R
```

```kotlin
Guard(secretBytes).use { guard ->
    sign(guard.bytes)
}   // secretBytes is zeroed
```
