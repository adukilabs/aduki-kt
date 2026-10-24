package pro.aduki.state.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Mailbox_
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Message_
import pro.aduki.sync.outbox.Manager

/**
 * Conversation is one thread of a mailbox: its newest message and counts.
 */
data class Conversation(
    val latest: Message,
    val count: Int,
    val unread: Int
)

/**
 * MailSource abstracts reactive entity streams.
 */
interface MailSource {
    fun mailboxes(): Flow<List<Mailbox>>
    fun messages(mailboxHex: String): Flow<List<Message>>
    fun message(hex: String): Flow<Message?>
    fun getMessage(hex: String): Message?
    fun getMailbox(hex: String): Mailbox?

    /** The messages of a mailbox now, unordered. */
    fun getMessages(mailboxHex: String): List<Message> = emptyList()

    /** Every mailbox now. */
    fun getMailboxes(): List<Mailbox> = emptyList()

    /** Every message, in every mailbox. */
    fun messages(): Flow<List<Message>> = emptyFlow()

    /** The messages of one thread, in any mailbox. */
    fun thread(threadId: String): Flow<List<Message>> = emptyFlow()
}

/**
 * Mail repository exposing hot StateFlow reactive query pipelines.
 */
class Mail(
    private val source: MailSource,
    private val manager: Manager? = null,
    private val scope: CoroutineScope
) {

    constructor(store: BoxStore, manager: Manager? = null, scope: CoroutineScope) : this(
        source = object : MailSource {
            private val mailboxBox = store.boxFor(Mailbox::class.java)
            private val messageBox = store.boxFor(Message::class.java)

            override fun mailboxes(): Flow<List<Mailbox>> = callbackFlow {
                val query = mailboxBox.query().build() // names are sealed: sorted in memory
                val sub = query.subscribe().observer { data -> trySend(data.sortedBy { it.name }) }
                awaitClose { sub.cancel() }
            }

            override fun messages(mailboxHex: String): Flow<List<Message>> = callbackFlow {
                val query = messageBox.query(Message_.mailbox.equal(mailboxHex))
                    .orderDesc(Message_.receivedAt)
                    .build()
                val sub = query.subscribe().observer { data -> trySend(data) }
                awaitClose { sub.cancel() }
            }

            override fun message(hex: String): Flow<Message?> = callbackFlow {
                val query = messageBox.query(Message_.hex.equal(hex)).build()
                val sub = query.subscribe().observer { data -> trySend(data.firstOrNull()) }
                awaitClose { sub.cancel() }
            }

            override fun getMessage(hex: String): Message? {
                return messageBox.query(Message_.hex.equal(hex)).build().use { it.findFirst() }
            }

            override fun getMailbox(hex: String): Mailbox? {
                return mailboxBox.query(Mailbox_.hex.equal(hex)).build().use { it.findFirst() }
            }

            override fun getMailboxes(): List<Mailbox> = mailboxBox.all

            override fun messages(): Flow<List<Message>> = callbackFlow {
                val query = messageBox.query().build()
                val sub = query.subscribe().observer { data -> trySend(data) }
                awaitClose { sub.cancel() }
            }

            override fun thread(threadId: String): Flow<List<Message>> = callbackFlow {
                val query = messageBox.query(Message_.threadId.equal(threadId)).build()
                val sub = query.subscribe().observer { data -> trySend(data) }
                awaitClose { sub.cancel() }
            }

            override fun getMessages(mailboxHex: String): List<Message> =
                messageBox.query(Message_.mailbox.equal(mailboxHex)).build().use { it.find() }
        },
        manager = manager,
        scope = scope
    )

    private val mailboxesFlow: StateFlow<List<Mailbox>> by lazy {
        source.mailboxes().stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )
    }

    private val messagesCache = ConcurrentHashMap<String, StateFlow<List<Message>>>()
    private val messageCache = ConcurrentHashMap<String, StateFlow<Message?>>()
    private val unreadCache = ConcurrentHashMap<String, StateFlow<Int>>()

    /**
     * Hot StateFlow of all mailboxes.
     */
    fun mailboxes(): StateFlow<List<Mailbox>> = mailboxesFlow

    /**
     * Hot StateFlow of messages for a given mailbox sorted newest first.
     */
    fun messages(mailboxHex: String): StateFlow<List<Message>> {
        return messagesCache.getOrPut(mailboxHex) {
            // `\Deleted` messages await expunge; they never show in a list.
            source.messages(mailboxHex).map { list -> list.filterNot { it.deleted() } }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )
        }
    }

    /**
     * Hot StateFlow of a single message by hex.
     */
    fun message(hex: String): StateFlow<Message?> {
        return messageCache.getOrPut(hex) {
            source.message(hex).stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = source.getMessage(hex)
            )
        }
    }

    /**
     * Hot StateFlow of unread count for a given mailbox.
     */
    fun unread(mailboxHex: String): StateFlow<Int> {
        return unreadCache.getOrPut(mailboxHex) {
            source.messages(mailboxHex)
                .map { list -> list.count { !it.seen() && !it.deleted() } }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = 0
                )
        }
    }

    /**
     * Messages matching [query], newest first: every word must appear in the
     * subject, sender, recipients or preview (case-insensitive). The columns
     * are sealed, so matching runs in memory. Only [mailbox] when given.
     * Not cached: the flow lives while it is collected (and 5 s after).
     */
    fun search(query: String, mailbox: String? = null): StateFlow<List<Message>> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val rows = if (mailbox == null) source.messages() else source.messages(mailbox)
        return rows.map { list ->
            if (words.isEmpty()) return@map emptyList()
            list.filter { msg ->
                if (msg.deleted()) return@filter false
                val text = listOf(msg.subject, msg.fromName, msg.fromEmail, msg.to, msg.preview).joinToString("\n").lowercase()
                words.all { it in text }
            }.sortedByDescending { it.receivedAt }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /**
     * The messages of a thread across mailboxes, oldest first; deleted ones hidden.
     */
    fun thread(threadId: String): StateFlow<List<Message>> =
        source.thread(threadId)
            .map { list -> list.filterNot { it.deleted() }.sortedBy { it.receivedAt } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The threads of a mailbox, newest first: one [Conversation] per thread
     * with its latest message, message count and unread count.
     */
    fun conversations(mailboxHex: String): StateFlow<List<Conversation>> =
        source.messages(mailboxHex)
            .map { list ->
                list.filterNot { it.deleted() }
                    .groupBy { it.threadId.ifBlank { it.hex } }
                    .values
                    .map { group ->
                        Conversation(
                            latest = group.maxBy { it.receivedAt },
                            count = group.size,
                            unread = group.count { !it.seen() }
                        )
                    }
                    .sortedByDescending { it.latest.receivedAt }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The mailboxes now (not a flow), e.g. to find one by role or name. */
    fun listMailboxes(): List<Mailbox> = source.getMailboxes()

    /** The messages of a mailbox now (not a flow). */
    fun listMessages(mailboxHex: String): List<Message> = source.getMessages(mailboxHex)

    /**
     * Hexes of the unread messages of a mailbox, read from the store now.
     */
    fun unseen(mailboxHex: String): List<String> =
        source.getMessages(mailboxHex).filter { !it.seen() && !it.deleted() }.map { it.hex }

    /**
     * Toggles a flag optimistically via the Outbox Manager.
     */
    fun flag(hex: String, flag: Int) {
        manager?.flag(hex, flag)
    }

    /**
     * Moves a message optimistically via the Outbox Manager.
     */
    fun move(hex: String, dest: String) {
        manager?.move(hex, dest)
    }
}

typealias MailRepository = Mail

