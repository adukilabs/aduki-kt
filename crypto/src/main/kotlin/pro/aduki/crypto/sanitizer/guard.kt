package pro.aduki.crypto.sanitizer

import pro.aduki.core.memory.wipe
import java.io.Closeable

/**
 * Guard wraps sensitive mutable byte arrays and zeroes out memory upon close.
 */
class Guard(val bytes: ByteArray) : Closeable {
    override fun close() {
        bytes.wipe()
    }
}

/**
 * Executes block with a temporary Guard wrapper, ensuring deterministic wiping on completion or failure.
 */
inline fun <R> withGuard(bytes: ByteArray, block: (Guard) -> R): R {
    val guard = Guard(bytes)
    return try {
        block(guard)
    } finally {
        guard.close()
    }
}

