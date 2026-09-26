package pro.aduki.hermes.sync.outbox

import pro.aduki.hermes.core.retry.Jitter
import pro.aduki.hermes.store.entities.Outbox

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

        for (action in manager.pending()) {
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
            val hex = action.hex.ifBlank { extractHex(action) }
            manager.complete(action.id, hex)
            true
        } catch (e: Rejected) {
            // Permanent: drop it (and clear the message's dirty mark so the
            // next sync restores the server's state) and move on.
            manager.complete(action.id, action.hex.ifBlank { extractHex(action) })
            onRejected(action, e)
            true
        } catch (_: Exception) {
            val delay = jitter.next(action.attempts)
            manager.fail(action.id, delay)
            false
        }
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

