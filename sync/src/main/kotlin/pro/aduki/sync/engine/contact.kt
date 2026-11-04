package pro.aduki.sync.engine

import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.aduki.core.models.GroupRow
import pro.aduki.core.models.SyncProgress
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Contact_
import pro.aduki.store.entities.Group as GroupEntity
import pro.aduki.store.entities.Outbox
import pro.aduki.store.entities.Outbox_
import pro.aduki.store.entities.Sync
import pro.aduki.store.entities.Sync_
import pro.aduki.sync.outbox.Book
import pro.aduki.sync.reconcile.Reconcile

/**
 * ContactDelta models address book changes and ctag cursor.
 *
 * A transport hands the engine one delta per page ([ContactTransport.pages]);
 * each is applied in its own transaction as it arrives.
 */
data class ContactDelta(
    val changed: List<ContactEntity> = emptyList(),
    val removed: List<String> = emptyList(),
    /** The cursor to keep: stored once the last page ([more] false) is applied. */
    val ctag: String = "",
    /**
     * `changed` is (a page of) the complete address book, not a delta: once
     * the last page is applied, local contacts missing from every page of
     * the listing are removed.
     */
    val full: Boolean = false,
    /**
     * The server's whole group list, empty groups included; null when the
     * transport cannot read it (the stored groups are then left as they are).
     */
    val groups: List<GroupRow>? = null,
    /** Another page of the same pass follows. */
    val more: Boolean = false,
    /** How many contacts the full listing has, when the server says (its first page). */
    val total: Int? = null
)

/**
 * ContactTransport defines network transport for address book sync.
 */
fun interface ContactTransport {
    /** Everything changed since [ctag] (or the whole book, `full`), as one delta. */
    suspend fun fetch(tenant: String, ctag: String): ContactDelta

    /**
     * Hands [apply] each page of changes since [ctag] in order, the last one
     * with `more = false`. By default one page: [fetch].
     */
    suspend fun pages(tenant: String, ctag: String, apply: suspend (ContactDelta) -> Unit) {
        apply(fetch(tenant, ctag))
    }
}

/**
 * ContactStorage abstracts persistence for contact sync.
 *
 * The engine reads through [unchanged], [hexes] and [find] so that only the
 * rows a page changes are opened; their defaults go through [getContacts],
 * for storages written before them.
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

    /** Of [tags] (hex to etag), the hexes stored with that same etag. */
    fun unchanged(tags: Map<String, String>): Set<String> =
        getContacts().filter { tags[it.hex] == it.ctag }.map { it.hex }.toSet()

    /** Every stored hex. */
    fun hexes(): Set<String> = getContacts().map { it.hex }.toSet()

    /** The stored contacts among [hexes]. */
    fun find(hexes: Collection<String>): List<ContactEntity> {
        val wanted = hexes.toSet()
        return getContacts().filter { it.hex in wanted }
    }
}

/**
 * Contact synchronizer managing address book delta sync with ctag cursors.
 *
 * One pass reads the pages of the transport and applies each in one
 * transaction: rows whose etag (`ctag`) is already stored are skipped, the
 * others are merged and put, removed hexes are deleted by id. A full
 * listing sweeps the hexes it did not list only after its last page, so a
 * pass that fails half way removes nothing. [progress] follows it.
 */
class Contact(
    private val storage: ContactStorage,
    private val transport: ContactTransport
) {

    constructor(store: BoxStore, transport: ContactTransport) : this(storage(store), transport)

    private val lock = Mutex()
    private val state = MutableStateFlow<SyncProgress>(SyncProgress.Idle)

    /** What the address book sync is doing: idle, running (cards so far), failed or done. */
    val progress: StateFlow<SyncProgress> = state.asStateFlow()

    companion object {
        // ObjectBox caps the conditions of one query; pages are at most 500.
        private const val CHUNK = 200

        /** The ObjectBox-backed storage for [store]. */
        fun storage(store: BoxStore): ContactStorage = object : ContactStorage {
            private val syncBox = store.boxFor(Sync::class.java)
            private val contactBox = store.boxFor(ContactEntity::class.java)

            override fun getSync(target: String): Sync? =
                syncBox.query(Sync_.target.equal(target, StringOrder.CASE_SENSITIVE)).build().use { it.findFirst() }
            override fun putSync(sync: Sync) { syncBox.put(sync) }
            override fun getContacts(): List<ContactEntity> = contactBox.all
            override fun putContacts(contacts: List<ContactEntity>) { contactBox.put(contacts.map(Sealing::reindex)) }
            override fun removeContacts(hexes: List<String>) {
                for (part in hexes.distinct().chunked(CHUNK)) {
                    val ids = contactBox.query(Contact_.hex.oneOf(part.toTypedArray(), StringOrder.CASE_SENSITIVE)).build().use { it.findIds() }
                    if (ids.isNotEmpty()) contactBox.remove(*ids)
                }
            }
            override fun <T> tx(block: () -> T): T = store.callInTx(block)
            override fun putGroups(groups: List<GroupRow>) {
                val box = store.boxFor(GroupEntity::class.java)
                box.removeAll()
                box.put(groups.map { GroupEntity(name = it.name, count = it.count) })
            }
            override fun pending(): Set<String> = store.boxFor(Outbox::class.java)
                .query(Outbox_.action.startsWith(Book.PREFIX, StringOrder.CASE_SENSITIVE)).build()
                .use { it.property(Outbox_.hex).findStrings().toSet() }

            // Plain columns only: (hex = h AND ctag = e) OR ..., read as hexes, nothing unsealed.
            override fun unchanged(tags: Map<String, String>): Set<String> {
                val found = HashSet<String>()
                for (part in tags.entries.chunked(CHUNK)) {
                    val condition = part.map { (hex, etag) ->
                        Contact_.hex.equal(hex, StringOrder.CASE_SENSITIVE).and(Contact_.ctag.equal(etag, StringOrder.CASE_SENSITIVE))
                    }.reduce { a, b -> a.or(b) }
                    contactBox.query(condition).build().use { found += it.property(Contact_.hex).findStrings() }
                }
                return found
            }
            override fun hexes(): Set<String> =
                contactBox.query().build().use { it.property(Contact_.hex).findStrings().toSet() }
            override fun find(hexes: Collection<String>): List<ContactEntity> =
                hexes.distinct().chunked(CHUNK).flatMap { part ->
                    contactBox.query(Contact_.hex.oneOf(part.toTypedArray(), StringOrder.CASE_SENSITIVE)).build().use { it.find() }
                }
        }
    }

    /**
     * Performs incremental delta sync for contacts in the given tenant.
     * Throws what the transport throws; [progress] is then `Failed`, and the
     * pages applied before the failure stay.
     */
    suspend fun sync(tenant: String): Boolean = sync(tenant, "")

    /**
     * [sync] for the account [account] (its user hex): the sync cursor is
     * kept per account, so a store that another account synced before (an
     * in-process account switch) starts with a full listing, which also
     * drops the other account's contacts. Blank keeps the one shared cursor.
     */
    suspend fun sync(tenant: String, account: String): Boolean = lock.withLock {
        val target = if (account.isBlank()) "contacts" else "contacts/$account"
        val record = storage.getSync(target) ?: Sync(target = target, token = "")
        // A queued group-wide write (no hex) touches contacts this cannot
        // tell apart: apply nothing until it is sent.
        if ("" in storage.pending()) return@withLock true
        val before = state.value
        val pass = Pass(record)
        state.value = SyncProgress.Running()
        try {
            transport.pages(tenant, record.token) { delta ->
                pass.apply(delta)
                state.value = SyncProgress.Running(pass.done, pass.total)
            }
            state.value = SyncProgress.Done(System.currentTimeMillis(), pass.changed)
            true
        } catch (e: CancellationException) {
            state.value = before
            throw e
        } catch (e: Exception) {
            state.value = SyncProgress.Failed(e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            throw e
        }
    }

    // One sync pass over the pages of a transport.
    private inner class Pass(private val record: Sync) {
        var done = 0
        var changed = 0
        var total: Int? = null

        // Hexes listed by the full listing in progress.
        private val seen = HashSet<String>()
        private var listing = false
        // A row was left alone for a queued write: the cursor must not pass it.
        private var skipped = false

        fun apply(delta: ContactDelta) {
            if (delta.full && !listing) seen.clear()
            listing = delta.full
            if (delta.total != null) total = delta.total
            changed += storage.tx { page(delta) }
            done += delta.changed.size + delta.removed.size
        }

        private fun page(delta: ContactDelta): Int {
            // Contacts with a queued write keep their local state; the next
            // sync after it is sent picks up the server's.
            val pending = storage.pending()
            val rows = delta.changed.associateBy { it.hex }
            if (delta.full) seen += rows.keys
            if (rows.keys.any { it in pending } || delta.removed.any { it in pending }) skipped = true

            val removed = delta.removed.filter { it !in pending }.toMutableSet()
            if (delta.full && !delta.more) {
                removed += storage.hexes().filter { it !in seen && it !in pending }
            }
            if (removed.isNotEmpty()) storage.removeContacts(removed.toList())

            val incoming = rows.filterKeys { it !in pending }
            // A row without an etag cannot be compared: it is always put.
            val tags = incoming.mapValues { it.value.ctag }.filterValues { it.isNotBlank() }
            val same = if (tags.isEmpty()) emptySet() else storage.unchanged(tags)
            val fresh = incoming.filterKeys { it !in same }.values
            var put = 0
            if (fresh.isNotEmpty()) {
                val locals = storage.find(fresh.map { it.hex }).associateBy { it.hex }
                // A local copy newer than the server's is kept as it is.
                val merged = fresh.mapNotNull { server ->
                    val local = locals[server.hex]
                    Reconcile.contact(local, server).takeIf { it !== local }
                }
                if (merged.isNotEmpty()) storage.putContacts(merged)
                put = merged.size
            }

            // A group created here is queued without a hex, so nothing is
            // pending at all when this runs: the list is the server's.
            delta.groups?.takeIf { "" !in pending }?.let(storage::putGroups)

            // A full listing is kept only whole; a delta page can be resumed.
            // Skipped rows must be read again: no cursor then.
            when {
                skipped -> record.token = ""
                !delta.more || !delta.full -> record.token = delta.ctag
            }
            record.timestamp = System.currentTimeMillis()
            storage.putSync(record)
            return put + removed.size
        }
    }
}
