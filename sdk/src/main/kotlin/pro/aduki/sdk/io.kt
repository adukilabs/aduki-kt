package pro.aduki.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.aduki.core.errors.Faults

/**
 * Runs [block] on [Dispatchers.IO] and then frees what the local database
 * cached for that worker thread. ObjectBox keeps a reader per thread; left on
 * the pool's threads, closing the store from another thread aborts them,
 * which ObjectBox 5 reports as a usage error.
 *
 * Failures leave it as [guard] says.
 */
internal suspend fun <T> Aduki.io(block: suspend CoroutineScope.() -> T): T = guard {
    try {
        block()
    } finally {
        release()
    }
}

/**
 * Runs [block] off the main thread ([Dispatchers.IO]: blocking HTTP and
 * database calls never run on the caller's thread, so a call from the main
 * thread cannot fail with `NetworkOnMainThreadException`) and lets it fail
 * only as the public API documents: cancellation, `AdukiException`,
 * `IOException`, or `IllegalArgumentException`/`IllegalStateException` for a
 * wrong call. Anything else is wrapped in an `AdukiException` ([Faults.normal]).
 */
internal suspend fun <T> guard(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: Throwable) {
        throw Faults.normal(e)
    }
}
