package pro.aduki.sync.engine

import io.objectbox.BoxStore
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Mailbox_
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Message_
import pro.aduki.sync.reconcile.Reconcile

/**
 * MailboxDelta models incremental CONDSTORE/MODSEQ updates.
 */
data class MailboxDelta(
    val newUids: List<Long> = emptyList(),
    val changedUids: List<Long> = emptyList(),
    val removedUids: List<Long> = emptyList(),
    val modseq: Long = 0L,
    val uidvalidity: Long = 0L,
    val messages: List<Message> = emptyList(),
    /** More changes wait past [modseq]; the engine fetches again. */
    val more: Boolean = false
)

/**
 * MailboxTransport defines network transport for CONDSTORE sync.
 */
fun interface MailboxTransport {
    suspend fun fetch(mailbox: String, uidvalidity: Long, modseq: Long): MailboxDelta
}

/**
 * MailboxStorage abstracts persistence for CONDSTORE sync.
 */
interface MailboxStorage {
    fun getMailbox(hex: String): Mailbox?
    fun putMailbox(mailbox: Mailbox)
    fun getMessages(mailboxHex: String): List<Message>
    fun putMessages(messages: List<Message>)
    fun removeMessages(messages: List<Message>)
    fun clearMailbox(mailboxHex: String)
    fun <T> tx(block: () -> T): T

    /** Messages with any of [hexes], in any mailbox (a message moved in keeps its row). */
    fun findByHex(hexes: List<String>): List<Message> = emptyList()
}

/**
 * Mailbox synchronizer implementing RFC 7162 CONDSTORE / MODSEQ delta synchronization.
 */
class Mailbox(
    private val storage: MailboxStorage,
    private val transport: MailboxTransport
) {

    constructor(store: BoxStore, transport: MailboxTransport) : this(object : MailboxStorage {
        private val mailboxes = store.boxFor(Mailbox::class.java)
        private val messages = store.boxFor(Message::class.java)

        // Indexed queries: never scan the whole box on a sync.
        override fun getMailbox(hex: String): Mailbox? =
            mailboxes.query(Mailbox_.hex.equal(hex)).build().use { it.findFirst() }
        override fun putMailbox(mailbox: Mailbox) { mailboxes.put(mailbox) }
        override fun getMessages(mailboxHex: String): List<Message> =
            messages.query(Message_.mailbox.equal(mailboxHex)).build().use { it.find() }
        override fun putMessages(messages: List<Message>) { this.messages.put(messages) }
        override fun removeMessages(messages: List<Message>) { this.messages.remove(messages) }
        override fun clearMailbox(mailboxHex: String) {
            messages.query(Message_.mailbox.equal(mailboxHex)).build().use { it.remove() }
        }
        override fun <T> tx(block: () -> T): T = store.callInTx(block)
        override fun findByHex(hexes: List<String>): List<Message> {
            if (hexes.isEmpty()) return emptyList()
            return messages.query(Message_.hex.oneOf(hexes.toTypedArray())).build().use { it.find() }
        }
    }, transport)

    /**
     * Performs incremental delta sync for the given mailbox, following
     * `more` pages until the mailbox is current. Each page commits on its
     * own, so an interrupted sync resumes where it stopped.
     */
    suspend fun sync(mailboxHex: String): Boolean {
        repeat(MAX_PAGES) {
            val more = page(mailboxHex) ?: return false
            if (!more) return true
        }
        return true
    }

    /** Applies one page of changes; `null` when the mailbox is unknown locally. */
    private suspend fun page(mailboxHex: String): Boolean? {
        val mailbox = storage.getMailbox(mailboxHex) ?: return null

        val delta = transport.fetch(
            mailbox = mailboxHex,
            uidvalidity = mailbox.uidvalidity,
            modseq = mailbox.modseq
        )

        return storage.tx {
            // 1. UIDVALIDITY Mismatch Check (RFC 7162 Invalidation); also the first sync.
            if (delta.uidvalidity != mailbox.uidvalidity) {
                storage.clearMailbox(mailboxHex)
                mailbox.uidvalidity = delta.uidvalidity
                mailbox.modseq = delta.modseq
                storage.putMailbox(mailbox)
                if (delta.messages.isNotEmpty()) {
                    storage.putMessages(adopt(delta.messages.filter { it.uid in delta.newUids }))
                }
                return@tx delta.more
            }

            // 2. Remove purged UIDs
            val current = storage.getMessages(mailboxHex)
            val removed = current.filter { it.uid in delta.removedUids }
            if (removed.isNotEmpty()) {
                storage.removeMessages(removed)
            }
            val remaining = current - removed.toSet()

            // 3. Apply updates to changed UIDs with conflict resolution
            val changedMap = delta.messages.filter { it.uid in delta.changedUids }.associateBy { it.uid }
            val toUpdate = mutableListOf<Message>()
            for (localMsg in remaining) {
                val serverMsg = changedMap[localMsg.uid] ?: continue
                toUpdate.add(
                    localMsg.copy(
                        flags = Reconcile.flags(localMsg, serverMsg.flags),
                        keywords = if (localMsg.dirty) localMsg.keywords else serverMsg.keywords,
                        mailbox = Reconcile.mailbox(localMsg, serverMsg.mailbox),
                        modseq = serverMsg.modseq
                    )
                )
            }
            if (toUpdate.isNotEmpty()) {
                storage.putMessages(toUpdate)
            }

            // 4. Insert newly discovered UIDs
            val newMsgs = delta.messages.filter { it.uid in delta.newUids }
            if (newMsgs.isNotEmpty()) {
                storage.putMessages(adopt(newMsgs, remaining))
            }

            // 5. Advance mailbox modseq
            mailbox.modseq = delta.modseq
            storage.putMailbox(mailbox)
            delta.more
        }
    }

    /**
     * New server messages reuse the local row of the same message (known in
     * [local] or moved from another mailbox), so a move never duplicates it.
     */
    private fun adopt(incoming: List<Message>, local: List<Message> = emptyList()): List<Message> {
        val known = (local + storage.findByHex(incoming.map { it.hex })).associateBy { it.hex }
        return incoming.map { msg ->
            val existing = known[msg.hex]
            if (existing != null) msg.copy(id = existing.id) else msg
        }
    }

    companion object {
        /** Upper bound on pages per sync, against a server that never settles. */
        const val MAX_PAGES = 1000
    }
}
