package pro.aduki.sync.outbox

import kotlinx.coroutines.CancellationException
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.errors.Faults
import pro.aduki.core.retry.Jitter
import pro.aduki.store.entities.Outbox

/**
 * Dispatcher sends outbox actions over the network.
 */
fun interface Dispatcher {
    suspend fun dispatch(action: Outbox)
}

/**
 * Rejected is thrown by a [Dispatcher] when the server refused an action
 * for good (for example an invalid recipient): retrying cannot succeed, so
 * the [Worker] drops it instead of blocking every later action behind it.
 */
class Rejected(message: String, val code: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * Worker sequentially consumes and dispatches pending outbox actions with Decorrelated Jitter backoff.
 */
class Worker(
    private val manager: Manager,
    private val dispatcher: Dispatcher,
    private val jitter: Jitter = Jitter(),
    /** Told about each action the server rejected for good (e.g. to notify the user). */
    private val onRejected: (Outbox, Rejected) -> Unit = { _, _ -> }
) {

    /**
     * Drains all pending actions that are eligible for dispatch.
     * Returns the count of successfully dispatched actions.
     */
    suspend fun drain(): Int {
        val now = System.currentTimeMillis()
        var successCount = 0

        for (queued in manager.pending()) {
            // Re-read it: an earlier action in this drain may have rewritten it
            // (a mailbox or contact placeholder adopted its server hex) or removed it.
            val action = manager.entry(queued.id) ?: continue
            if (action.nextRetry > now) {
                // Break to preserve sequential outbox ordering
                break
            }
            val ok = process(action)
            if (ok) {
                successCount++
            } else {
                // Break on first failure to maintain sequential action ordering
                break
            }
        }
        return successCount
    }

    /**
     * Processes a single outbox action with backoff on failure.
     */
    suspend fun process(action: Outbox): Boolean {
        return try {
            dispatcher.dispatch(action)
            manager.complete(action.id, hexes(action))
            true
        } catch (e: CancellationException) {
            // The drain was cancelled: the action stays pending, untouched.
            throw e
        } catch (e: Rejected) {
            // Permanent: mark as failed, preserve in store for user inspection/retry/discard,
            // clear the message dirty mark, and move on.
            manager.reject(action.id, e.code, e.message.orEmpty(), hexes(action))
            try {
                onRejected(action, e)
            } catch (failure: Exception) {
                // The app's callback must not stop the queue or escape a background drain.
                Faults.report("outbox rejected callback", failure)
            }
            true
        } catch (e: Exception) {
            Faults.report("outbox ${action.action}", e)
            manager.fail(action.id, delay(action, e))
            false
        }
    }

    // The backoff before the next attempt: jittered, and never sooner than
    // the server's Retry-After (429, 503), capped at [MAX_WAIT].
    private fun delay(action: Outbox, e: Exception): Long {
        val backoff = jitter.next(action.attempts)
        val seconds = when (e) {
            is AdukiException.Network -> e.retryAfter
            is AdukiException.Api -> e.retryAfter
            else -> null
        } ?: return backoff
        return maxOf(backoff, minOf(seconds * 1000, MAX_WAIT))
    }

    private companion object {
        /** The longest a Retry-After may hold the queue back, ms (one hour). */
        const val MAX_WAIT = 3_600_000L
    }

    // The messages an action touched: its own hex, or the payload's `hex` or `hexes`.
    private fun hexes(action: Outbox): List<String> {
        if (action.hex.isNotBlank()) return listOf(action.hex)
        val many = try {
            val str = String(action.payload, Charsets.UTF_8)
            if (str.trimStart().startsWith("{")) org.json.JSONObject(str).optJSONArray("hexes") else null
        } catch (_: Exception) {
            null
        }
        if (many != null) return (0 until many.length()).map { many.getString(it) }
        return listOfNotNull(extractHex(action))
    }

    private fun extractHex(action: Outbox): String? {
        return try {
            val str = String(action.payload, Charsets.UTF_8)
            if (str.trimStart().startsWith("{")) {
                return org.json.JSONObject(str).optString("hex").ifBlank { null }
            }
            when (action.action) {
                "flag", "move" -> str.substringBefore(":")
                "delete" -> str
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

