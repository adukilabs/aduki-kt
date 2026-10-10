package pro.aduki.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Custom dispatchers tuned for low-latency Android performance.
 */
object Runners {
    /**
     * Dedicated single-threaded dispatcher for serialized write transactions to ObjectBox.
     */
    val store: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aduki-store").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    /**
     * Dedicated pool for network operations.
     */
    val net: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(16)

    /**
     * Dedicated pool for hardware cryptography and key derivation.
     */
    val crypto: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4)
}

