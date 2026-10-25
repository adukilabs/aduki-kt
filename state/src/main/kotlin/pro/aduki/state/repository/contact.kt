package pro.aduki.state.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact_
import pro.aduki.store.entities.Group as GroupEntity

/**
 * ContactSource abstracts reactive entity streams for contacts.
 */
interface ContactSource {
    fun contacts(): Flow<List<ContactEntity>>
    fun get(hex: String): ContactEntity?

    /** Exact lookup by e-mail address (case-insensitive); null when none. */
    fun byEmail(email: String): ContactEntity? = null

    /** Exact lookup by phone number (digits and `+` compared); null when none. */
    fun byPhone(phone: String): ContactEntity? = null

    /** Every stored contact, read now; empty when the source cannot list. */
    fun all(): List<ContactEntity> = emptyList()

    /** The names of the stored groups (`Group` rows, which keep groups without members). */
    fun groups(): Flow<List<String>> = flowOf(emptyList())

    /** The names of the stored groups, read now. */
    fun names(): List<String> = emptyList()
}

/**
 * Contact repository exposing reactive address book StateFlow query streams.
 */
class Contact(
    private val source: ContactSource,
    private val scope: CoroutineScope
) {

    constructor(store: BoxStore, scope: CoroutineScope) : this(
        source = object : ContactSource {
            private val box = store.boxFor(ContactEntity::class.java)

            override fun contacts(): Flow<List<ContactEntity>> = callbackFlow {
                val query = box.query().build() // names are sealed: sorted in memory
                val sub = query.subscribe().observer { data ->
                    trySend(data)
                }
                awaitClose { sub.cancel() }
            }

            override fun get(hex: String): ContactEntity? =
                box.query(Contact_.hex.equal(hex)).build().use { it.findFirst() }

            override fun all(): List<ContactEntity> = box.all

            private val groupBox = store.boxFor(GroupEntity::class.java)

            override fun groups(): Flow<List<String>> = callbackFlow {
                val sub = groupBox.query().build().subscribe().observer { data -> trySend(data.map { it.name }) }
                awaitClose { sub.cancel() }
            }

            override fun names(): List<String> = groupBox.all.map { it.name }

            // Through the keyed blind index when the vault is attached; otherwise
            // a scan over the decrypted rows (every contact is read and opened).
            override fun byEmail(email: String): ContactEntity? {
                val key = Sealing.index("email", email)
                if (key.isNotEmpty()) return box.query(Contact_.emailIndex.equal(key)).build().use { it.findFirst() }
                val q = email.trim().lowercase()
                return if (q.isEmpty()) null else box.all.firstOrNull { it.email.trim().lowercase() == q }
            }

            override fun byPhone(phone: String): ContactEntity? {
                val key = Sealing.index("phone", phone)
                if (key.isNotEmpty()) return box.query(Contact_.phoneIndex.equal(key)).build().use { it.findFirst() }
                val q = phone.filter { it.isDigit() || it == '+' }
                return if (q.isEmpty()) null else box.all.firstOrNull { c -> c.phone.filter { it.isDigit() || it == '+' } == q }
            }
        },
        scope = scope
    )

    private val contactsFlow: StateFlow<List<ContactEntity>> by lazy {
        source.contacts()
            .map { list -> list.sortedBy { it.name } }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )
    }

    private val searchCache = ConcurrentHashMap<String, StateFlow<List<ContactEntity>>>()
    private val groupCache = ConcurrentHashMap<String, StateFlow<List<ContactEntity>>>()

    // Group names are the tags on contacts plus the stored groups (which
    // keep a group without members listed).
    private val groupsFlow: StateFlow<List<String>> by lazy {
        combine(source.contacts(), source.groups()) { list, names -> merge(list.flatMap { it.groups } + names) }
            .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = emptyList())
    }

    private fun merge(names: List<String>): List<String> = names.distinct().sortedBy { it.lowercase() }

    /**
     * Hot StateFlow of all contacts sorted by name.
     */
    fun observe(): StateFlow<List<ContactEntity>> = contactsFlow

    /**
     * Hot StateFlow of contacts matching a search prefix or substring.
     */
    fun search(query: String): StateFlow<List<ContactEntity>> {
        val q = query.trim().lowercase()
        return searchCache.getOrPut(q) {
            source.contacts()
                .map { list ->
                    if (q.isEmpty()) {
                        list.sortedBy { it.name }
                    } else {
                        list.filter { c ->
                            c.name.lowercase().contains(q) ||
                                c.company.lowercase().contains(q) ||
                                c.email.lowercase().contains(q) ||
                                c.phone.lowercase().contains(q) ||
                                c.notes.lowercase().contains(q) ||
                                c.emails.any { it.lowercase().contains(q) } ||
                                c.phones.any { it.lowercase().contains(q) } ||
                                c.groups.any { it.lowercase().contains(q) }
                        }.sortedBy { it.name }
                    }
                }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyList()
                )
        }
    }

    /** Hot StateFlow of the members of [group], sorted by name. */
    fun group(group: String): StateFlow<List<ContactEntity>> = groupCache.getOrPut(group) {
        source.contacts()
            .map { list -> list.filter { group in it.groups }.sortedBy { it.name } }
            .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = emptyList())
    }

    /** Hot StateFlow of the group names (in use, or kept without members), sorted. */
    fun groups(): StateFlow<List<String>> = groupsFlow

    /** The group names now (in use, or kept without members), sorted. */
    fun names(): List<String> = merge(source.all().flatMap { it.groups } + source.names())

    /** Every stored contact, read now. */
    fun all(): List<ContactEntity> = source.all()

    /**
     * Looks up a contact synchronously by hex.
     */
    fun get(hex: String): ContactEntity? = source.get(hex)

    /** The contact with exactly this e-mail address, or null. */
    fun byEmail(email: String): ContactEntity? = source.byEmail(email)

    /** The contact with exactly this phone number, or null. */
    fun byPhone(phone: String): ContactEntity? = source.byPhone(phone)
}

typealias ContactRepository = Contact

