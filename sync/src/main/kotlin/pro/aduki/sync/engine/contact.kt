package pro.aduki.sync.engine

import io.objectbox.BoxStore
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Sync
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
    val full: Boolean = false
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
            override fun putContacts(contacts: List<ContactEntity>) { contactBox.put(contacts) }
            override fun removeContacts(hexes: List<String>) {
                val toRemove = contactBox.all.filter { it.hex in hexes }
                contactBox.remove(toRemove)
            }
            override fun <T> tx(block: () -> T): T = store.callInTx(block)
        }
    }

    /**
     * Performs incremental delta sync for contacts in the given tenant.
     */
    suspend fun sync(tenant: String): Boolean {
        val syncRecord = storage.getSync("contacts") ?: Sync(target = "contacts", token = "")
        val delta = transport.fetch(tenant, syncRecord.token)

        return storage.tx {
            val removed = if (delta.full) {
                val kept = delta.changed.map { it.hex }.toSet()
                delta.removed + storage.getContacts().map { it.hex }.filter { it !in kept }
            } else {
                delta.removed
            }
            if (removed.isNotEmpty()) {
                storage.removeContacts(removed)
            }

            if (delta.changed.isNotEmpty()) {
                val existingMap = storage.getContacts().associateBy { it.hex }
                val merged = delta.changed.map { serverContact ->
                    val local = existingMap[serverContact.hex]
                    Reconcile.contact(local, serverContact)
                }
                storage.putContacts(merged)
            }

            syncRecord.token = delta.ctag
            syncRecord.timestamp = System.currentTimeMillis()
            storage.putSync(syncRecord)
            true
        }
    }
}

