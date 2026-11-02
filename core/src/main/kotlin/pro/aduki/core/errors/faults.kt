package pro.aduki.core.errors

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Faults keeps what the SDK's public calls throw to the documented set
 * (see `docs/resilience.md`), and logs failures of background work.
 *
 * A public call fails only with:
 * - a [CancellationException] (the caller was cancelled), unchanged;
 * - an [AdukiException], unchanged;
 * - an [IOException] (offline, DNS, TLS, timeout, a stream cut short), unchanged;
 * - an [IllegalArgumentException] or [IllegalStateException] for a call the
 *   app made wrongly (an invalid argument, no local store), unchanged.
 *
 * Anything else (a malformed server answer read as `JSONException`,
 * `NoSuchElementException`, `ClassCastException`, `NumberFormatException`,
 * `DateTimeException`, an `IndexOutOfBoundsException`, a local database
 * failure, ...) is wrapped: [AdukiException.Storage] for an ObjectBox failure,
 * [AdukiException.Protocol] otherwise, with the original as the cause.
 * [Error]s are left alone.
 */
object Faults {
    private val log: Logger = Logger.getLogger("pro.aduki")

    /** [e] as a public call may throw it (see above). */
    fun normal(e: Throwable): Throwable = when (e) {
        is CancellationException, is AdukiException, is IOException, is Error -> e
        // A number read from a server string; not a caller's mistake.
        is NumberFormatException -> AdukiException.Protocol(unexpected(e), e)
        is IllegalArgumentException, is IllegalStateException -> e
        else -> if (e.javaClass.name.startsWith("io.objectbox.")) {
            AdukiException.Storage("Local database failure (${e.javaClass.simpleName})", e)
        } else {
            AdukiException.Protocol(unexpected(e), e)
        }
    }

    /**
     * Logs that background work [where] failed with [e]. Only the exception
     * type is written, never its message: messages can hold server text or
     * addresses. Cancellation is not a failure and is not logged.
     */
    fun report(where: String, e: Throwable) {
        if (e is CancellationException) return
        val cause = e.cause?.let { " (cause ${it.javaClass.name})" }.orEmpty()
        log.log(Level.WARNING, "aduki: $where failed: ${e.javaClass.name}$cause")
    }

    private fun unexpected(e: Throwable) = "Unexpected ${e.javaClass.simpleName} (a malformed server answer or an SDK fault)"
}
