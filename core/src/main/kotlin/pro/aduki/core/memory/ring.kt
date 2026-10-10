package pro.aduki.core.memory

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) ring buffer.
 * Provides lock-free O(1) queuing for high-frequency events and outbox actions.
 */
class Ring<T : Any>(capacity: Int = 1024) {
    private val bufferCapacity = nextPowerOfTwo(capacity)
    private val mask = bufferCapacity - 1

    @Suppress("UNCHECKED_CAST")
    private val entries = arrayOfNulls<Any>(bufferCapacity) as Array<T?>

    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)

    /**
     * Enqueues an item without acquiring thread locks.
     * Returns true if enqueued, false if the ring buffer is full.
     */
    fun offer(item: T): Boolean {
        val currentTail = tail.get()
        val currentHead = head.get()

        if (currentTail - currentHead >= bufferCapacity) {
            return false // Buffer full
        }

        val index = (currentTail and mask.toLong()).toInt()
        entries[index] = item
        tail.lazySet(currentTail + 1)
        return true
    }

    /**
     * Dequeues an item without acquiring thread locks.
     * Returns item if present, or null if the ring buffer is empty.
     */
    fun poll(): T? {
        val currentHead = head.get()
        val currentTail = tail.get()

        if (currentHead >= currentTail) {
            return null // Buffer empty
        }

        val index = (currentHead and mask.toLong()).toInt()
        val item = entries[index]
        entries[index] = null
        head.lazySet(currentHead + 1)
        return item
    }

    fun size(): Int {
        return (tail.get() - head.get()).toInt()
    }

    fun empty(): Boolean = head.get() == tail.get()

    private fun nextPowerOfTwo(value: Int): Int {
        if (value <= 1) return 1
        var v = value - 1
        v = v or (v ushr 1)
        v = v or (v ushr 2)
        v = v or (v ushr 4)
        v = v or (v ushr 8)
        v = v or (v ushr 16)
        return v + 1
    }
}

