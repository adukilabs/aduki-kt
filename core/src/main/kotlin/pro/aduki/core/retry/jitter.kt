package pro.aduki.core.retry

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * Decorrelated Jitter exponential backoff algorithm to prevent client retry synchronization waves.
 */
class Jitter(
    val base: Long = 100,
    val max: Long = 30_000,
    private val random: Random = Random.Default
) {
    private val delay = AtomicLong(base)

    /**
     * Calculates the next backoff delay.
     */
    fun next(attempt: Int = 0): Long {
        return delay.updateAndGet { current ->
            val bound = if (current * 3 > base) current * 3 else base + 1
            val next = random.nextLong(base, bound)
            min(max, next)
        }
    }

    /**
     * Resets delay to base.
     */
    fun reset() {
        delay.set(base)
    }

    /**
     * Current delay value.
     */
    fun current(): Long = delay.get()
}

/**
 * Typealias for DecorrelatedJitter.
 */
typealias DecorrelatedJitter = Jitter
