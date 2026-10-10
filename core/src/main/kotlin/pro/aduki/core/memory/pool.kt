package pro.aduki.core.memory

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe memory buffer pool recycling ByteArrays to prevent Android GC pauses.
 */
class Pool(
    private val bufferSize: Int = 16 * 1024, // 16 KB default page
    private val maxCapacity: Int = 32
) {
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    /**
     * Obtains an allocated or recycled byte buffer.
     */
    fun acquire(): ByteArray {
        return queue.poll() ?: ByteArray(bufferSize)
    }

    /**
     * Returns a buffer to the pool after zeroing its contents.
     */
    fun release(buffer: ByteArray) {
        if (buffer.size == bufferSize && queue.size < maxCapacity) {
            buffer.wipe()
            queue.offer(buffer)
        }
    }

    inline fun <R> use(block: (ByteArray) -> R): R {
        val buffer = acquire()
        try {
            return block(buffer)
        } finally {
            release(buffer)
        }
    }
}

