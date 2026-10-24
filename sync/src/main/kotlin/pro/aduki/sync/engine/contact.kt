package pro.aduki.sync.engine

import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder
import pro.aduki.core.models.GroupRow
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Group as GroupEntity
import pro.aduki.store.entities.Outbox
import pro.aduki.store.entities.Outbox_
import pro.aduki.store.entities.Sync
import pro.aduki.sync.outbox.Book
import pro.aduki.sync.reconcile.Reconcile

/**
 * ContactDelta models address book changes and ctag cursor.
 */
data class ContactDelta(
    val changed: List<ContactEntity> = emptyList(),
    val removed: List<String> = emptyList(),
    val ctag: String = "",
    /**
     * `changed` is the complete address book, not a delta: local contacts
     * missing from it are removed. For transports whose server has no
     * incremental route.
     */
    val full: Boolean = false,
    /**
     * The server's whole group list, empty groups included; null when the
     * transport cannot read it (the stored groups are then left as they are).
     */
    val groups: List<GroupRow>? = null
)

/**
 * ContactTransport defines network transport for address book sync.
 */
fun interface ContactTransport {
    suspend fun fetch(tenant: String, ctag: String): ContactDelta
}

/**
 * ContactStorage abstracts persistence for contact sync.
 */
interface ContactStorage {
    fun getSync(target: String): Sync?
    fun putSync(sync: Sync)
    fun getContacts(): List<ContactEntity>
    fun putContacts(contacts: List<ContactEntity>)
    fun removeContacts(hexes: List<String>)
    fun <T> tx(block: () -> T): T

    /** Replaces the stored groups (the rows that keep empty groups listed) with [groups]. */
    fun putGroups(groups: List<GroupRow>) {}

    /**
     * Hexes of contacts with a queued outbox write: a sync leaves them as
     * they are locally until the write is sent. "" stands for a group-wide
     * write, which holds the whole sync back.
     */
    fun pending(): Set<String> = emptySet()
}

/**
 * Contact synchronizer managing address book delta sync with ctag cursors.
 */
class Contact(
    private val storage: ContactStorage,
    private val transport: ContactTransport
) {

    constructor(store: BoxStore, transport: ContactTransport) : this(storage(store), transport)

    companion object {
        /** The ObjectBox-backed storage for [store]. */
        fun storage(store: BoxStore): ContactStorage = object : ContactStorage {
            private val syncBox = store.boxFor(Sync::class.java)
            private val contactBox = store.boxFor(ContactEntity::class.java)

            override fun getSync(target: String): Sync? = syncBox.all.firstOrNull { it.target == target }
            override fun putSync(sync: Sync) { syncBox.put(sync) }
            override fun getContacts(): List<ContactEntity> = contactBox.all
            override fun putContacts(contacts: List<ContactEntity>) { contactBox.put(contacts.map(Sealing::reindex)) }
            override fun removeContacts(hexes: List<String>) {
                val toRemove = contactBox.all.filter { it.hex in hexes }
                contactBox.remove(toRemove)
            }
            override fun <T> tx(block: () -> T): T = store.callInTx(block)
            override fun putGroups(groups: List<GroupRow>) {
                val box = store.boxFor(GroupEntity::class.java)
                box.removeAll()
                box.put(groups.map { GroupEntity(name = it.name, count = it.count) })
            }
            override fun pending(): Set<String> = store.boxFor(Outbox::class.java)
                .query(Outbox_.action.startsWith(Book.PREFIX, StringOrder.CASE_SENSITIVE)).build()
                .property(Outbox_.hex).findStrings().toSet()
        }
    }

    /**
     * Performs incremental delta sync for contacts in the given tenant.
     */
    suspend fun sync(tenant: String): Boolean {
        val syncRecord = storage.getSync("contacts") ?: Sync(target = "contacts", token = "")
        // A queued group-wide write (no hex) touches contacts this cannot
        // tell apart: apply nothing until it is sent.
        if ("" in storage.pending()) return true
        val delta = transport.fetch(tenant, syncRecord.token)

        return storage.tx {
            // Contacts with a queued write keep their local state; the next
            // sync after it is sent picks up the server's.
            val pending = storage.pending()
            val removed = if (delta.full) {
                val kept = delta.changed.map { it.hex }.toSet()
                delta.removed + storage.getContacts().map { it.hex }.filter { it !in kept }
            } else {
                delta.removed
            }.filter { it !in pending }
            if (removed.isNotEmpty()) {
                storage.removeContacts(removed)
            }

            val changed = delta.changed.filter { it.hex !in pending }
            if (changed.isNotEmpty()) {
                val existingMap = storage.getContacts().associateBy { it.hex }
                val merged = changed.map { serverContact ->
                    val local = existingMap[serverContact.hex]
                    Reconcile.contact(local, serverContact)
                }
                storage.putContacts(merged)
            }

            // A group created here is queued without a hex, so nothing is
            // pending at all when this runs: the list is the server's.
            delta.groups?.takeIf { "" !in pending }?.let(storage::putGroups)

            // Skipped rows must be read again: no cursor until nothing is pending.
            syncRecord.token = if (pending.isEmpty()) delta.ctag else ""
            syncRecord.timestamp = System.currentTimeMillis()
            storage.putSync(syncRecord)
            true
        }
    }
}

