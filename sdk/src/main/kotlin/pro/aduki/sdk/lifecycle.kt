package pro.aduki.sdk

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lifecycle monitors application foreground and background states, pausing or resuming sync.
 */
class Lifecycle {
    // Read from any thread; changed under the lock so two racing calls
    // cannot both see the old state and notify twice.
    @Volatile
    private var state: Boolean = true
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * Returns true if application is in foreground / active.
     */
    fun active(): Boolean = state

    /**
     * Transitions to background: suspends background polling and sync tasks.
     */
    fun pause() {
        val changed = synchronized(lock) { state.also { state = false } }
        if (changed) listeners.forEach { it(false) }
    }

    /**
     * Transitions to foreground: reactivates polling and triggers sync flush.
     */
    fun resume() {
        val changed = synchronized(lock) { (!state).also { state = true } }
        if (changed) listeners.forEach { it(true) }
    }

    /**
     * Registers a lifecycle state change listener.
     */
    fun listen(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Unregisters a lifecycle state change listener.
     */
    fun remove(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}

