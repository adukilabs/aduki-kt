package pro.aduki.state.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Mailbox_
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Message_
import pro.aduki.sync.outbox.Manager

/**
 * MailSource abstracts reactive entity streams.
 */
interface MailSource {
    fun mailboxes(): Flow<List<Mailbox>>
    fun messages(mailboxHex: String): Flow<List<Message>>
    fun message(hex: String): Flow<Message?>
    fun getMessage(hex: String): Message?
    fun getMailbox(hex: String): Mailbox?
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
                return messageBox.query(Message_.hex.equal(hex)).build().findFirst()
            }

            override fun getMailbox(hex: String): Mailbox? {
                return mailboxBox.query(Mailbox_.hex.equal(hex)).build().findFirst()
            }
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

