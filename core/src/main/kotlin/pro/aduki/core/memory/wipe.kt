package pro.aduki.core.memory

import java.util.Arrays

/**
 * Deterministic memory zeroization for sensitive primitive arrays.
 * Neutralizes memory inspection and heap dump attacks on Android devices.
 */
inline fun <R> withWipedBytes(size: Int, block: (ByteArray) -> R): R {
    val buffer = ByteArray(size)
    try {
        return block(buffer)
    } finally {
        buffer.wipe()
    }
}

inline fun <R> withWipedChars(chars: CharArray, block: (CharArray) -> R): R {
    try {
        return block(chars)
    } finally {
        chars.wipe()
    }
}

fun ByteArray.wipe() {
    Arrays.fill(this, 0.toByte())
}

fun CharArray.wipe() {
    Arrays.fill(this, '\u0000')
}

fun IntArray.wipe() {
    Arrays.fill(this, 0)
}

fun LongArray.wipe() {
    Arrays.fill(this, 0L)
}
