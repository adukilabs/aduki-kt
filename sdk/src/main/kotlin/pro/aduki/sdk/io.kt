package pro.aduki.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [block] on [Dispatchers.IO] and then frees what the local database
 * cached for that worker thread. ObjectBox keeps a reader per thread; left on
 * the pool's threads, closing the store from another thread aborts them,
 * which ObjectBox 5 reports as a usage error.
 */
internal suspend fun <T> Aduki.io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } finally {
        release()
    }
}
