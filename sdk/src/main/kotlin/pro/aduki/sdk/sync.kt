package pro.aduki.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.sync.engine.Mailbox as MailboxEngine
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Worker

/**
 * SyncState is what a sync is doing, for progress and error UI.
 */
data class SyncState(
    /** A sync is in progress. */
    val running: Boolean = false,
    /** Epoch millis of the last sync that finished without error, 0 if none. */
    val synced: Long = 0,
    /** Why the last sync failed; null after a sync that succeeded. */
    val error: Throwable? = null,
    /**
     * Mailboxes the last [Sync.all] was asked for but found neither locally
     * nor on the server (a hex or role); [Sync.all] returned false for them.
     * A fetch failure is thrown instead, so the two never mix.
     */
    val missing: List<String> = emptyList()
)

/**
 * Sync service coordinating CONDSTORE/MODSEQ mail sync and address book delta sync.
 */
class Sync internal constructor(
    private val client: Aduki,
    private val mailboxEngine: MailboxEngine? = null,
    private val contactEngine: ContactEngine? = null,
    private val worker: Worker? = null,
    private val manager: Manager? = null
) {
    private val lock = Mutex()
    private val current = MutableStateFlow(SyncState())

    /** Running, last success and last error of [all]. */
    val state: StateFlow<SyncState> = current.asStateFlow()

    /** The number of queued outbox actions; always 0 without a local store. */
    val outbox: StateFlow<Int> = manager?.count ?: MutableStateFlow(0).asStateFlow()

    /**
     * Loads the mailbox list into the local store (`GET /user/mailbox`) and
     * returns the mailbox hexes. Known mailboxes keep their sync cursor;
     * mailboxes deleted on the server are dropped with their messages.
     */
    suspend fun mailboxes(): List<String> = mailboxEngine?.refresh() ?: emptyList()

    /**
     * Runs incremental delta sync across mailboxes and the address book.
     *
     * With no [mailboxes], the mailbox list is loaded first and every mailbox
     * syncs. A listed mailbox is a hex or a role (`inbox`, `sent`, ...); an
     * unknown one loads the mailbox list once. Returns false when a listed
     * mailbox does not exist ([SyncState.missing] names it). Snoozed messages that are due come back to the
     * inbox ([Mail.wake]). Failures are rethrown and kept in [state].
     */
    suspend fun all(mailboxes: List<String>? = null): Boolean = lock.withLock {
        current.update { it.copy(running = true) }
        try {
            val missing = mutableListOf<String>()
            val engine = mailboxEngine
            if (engine != null) {
                val names = mailboxes ?: engine.refresh()
                val targets = if (mailboxes == null) names else resolve(engine, mailboxes)
                for ((name, box) in names.zip(targets)) {
                    // Resolved but gone by now (deleted meanwhile): also missing.
                    if (box == null || !engine.sync(box)) missing += name
                }
            }
            client.mail.wake()
            if (contactEngine != null) {
                val tenant = client.me()?.tenant ?: ""
                if (tenant.isNotBlank()) {
                    contactEngine.sync(tenant)
                }
            }
            current.value = SyncState(synced = System.currentTimeMillis(), missing = missing)
            missing.isEmpty()
        } catch (e: Exception) {
            if (e !is CancellationException) current.update { it.copy(error = e) }
            throw e
        } finally {
            current.update { it.copy(running = false) }
        }
    }

    /** Hex for each of [names] (a hex or a role), null where none exists. */
    private suspend fun resolve(engine: MailboxEngine, names: List<String>): List<String?> {
        fun find(name: String) = if (engine.known(name)) name else engine.role(name.lowercase())
        if (names.any { find(it) == null }) engine.refresh()
        return names.map(::find)
    }

    /**
     * Flushes all pending outbox mutations over the network.
     */
    suspend fun flush(): Int {
        return worker?.drain() ?: 0
    }

    /**
     * Returns the count of pending outbox mutations; [outbox] observes it.
     */
    fun pending(): Int {
        return manager?.pending()?.size ?: 0
    }
}
