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
 * MailboxDirectory lists the account's mailboxes on the server.
 */
fun interface MailboxDirectory {
    suspend fun list(): List<Mailbox>
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

    /** Every local mailbox. */
    fun mailboxes(): List<Mailbox> = emptyList()

    /** Drops a mailbox row (its messages are cleared separately). */
    fun removeMailbox(mailbox: Mailbox) {}
}

/**
 * Mailbox synchronizer implementing RFC 7162 CONDSTORE / MODSEQ delta synchronization.
 */
class Mailbox(
    private val storage: MailboxStorage,
    private val transport: MailboxTransport,
    /** Lists mailboxes for [refresh]; without it only known rows sync. */
    private val directory: MailboxDirectory? = transport as? MailboxDirectory
) {

    constructor(
        store: BoxStore,
        transport: MailboxTransport,
        directory: MailboxDirectory? = transport as? MailboxDirectory
    ) : this(storage(store), transport, directory)

    /**
     * Loads the server's mailbox list into the store and returns their hexes.
     * Known rows keep their sync cursor (`uidvalidity`, `modseq`); mailboxes
     * gone from the server are dropped with their messages. Without a
     * [directory], returns the local rows unchanged.
     */
    suspend fun refresh(): List<String> {
        val remote = directory?.list() ?: return storage.mailboxes().map { it.hex }
        return storage.tx {
            val local = storage.mailboxes().associateBy { it.hex }
            val kept = remote.map { it.hex }.toSet()
            // A mailbox created offline is not on the server yet: keep it.
            for (gone in local.values.filter { it.hex !in kept && !it.pending() }) {
                storage.clearMailbox(gone.hex)
                storage.removeMailbox(gone)
            }
            for (box in remote) {
                val row = local[box.hex]
                storage.putMailbox(
                    row?.copy(
                        name = box.name,
                        role = box.role,
                        uidnext = box.uidnext,
                        exists = box.exists,
                        unseen = box.unseen
                    ) ?: box.copy(id = 0, uidvalidity = 0, modseq = 0)
                )
            }
            remote.map { it.hex }
        }
    }

    /** The hex of the local mailbox with [role] (`inbox`, `sent`, ...), or null. */
    fun role(role: String): String? = storage.mailboxes().firstOrNull { it.role == role }?.hex

    /** Whether a mailbox row with [hex] is stored. */
    fun known(hex: String): Boolean = storage.getMailbox(hex) != null

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

        /** The ObjectBox-backed storage for [store]. */
        fun storage(store: BoxStore): MailboxStorage = object : MailboxStorage {
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
            override fun mailboxes(): List<Mailbox> = mailboxes.all
            override fun removeMailbox(mailbox: Mailbox) { mailboxes.remove(mailbox) }
        }
    }
}
