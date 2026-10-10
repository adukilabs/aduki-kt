package pro.aduki.core.retry

import pro.aduki.core.errors.AdukiException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Three-state circuit breaker to prevent radio battery drain during network outages.
 */
class Circuit(
    private val threshold: Int = 5,
    private val timeout: Long = 15_000
) {
    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private val state = AtomicReference(State.CLOSED)
    private val failures = AtomicInteger(0)
    private val changed = AtomicLong(System.currentTimeMillis())

    fun state(): State {
        val now = System.currentTimeMillis()
        if (state.get() == State.OPEN && (now - changed.get()) >= timeout) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN)
        }
        return state.get()
    }

    fun success() {
        failures.set(0)
        state.set(State.CLOSED)
        changed.set(System.currentTimeMillis())
    }

    fun fail() {
        val count = failures.incrementAndGet()
        if (count >= threshold || state.get() == State.HALF_OPEN) {
            state.set(State.OPEN)
            changed.set(System.currentTimeMillis())
        }
    }

    inline fun <T> execute(block: () -> T): T {
        if (state() == State.OPEN) {
            throw AdukiException.CircuitOpen()
        }
        return try {
            val result = block()
            success()
            result
        } catch (e: Throwable) {
            fail()
            throw e
        }
    }
}

