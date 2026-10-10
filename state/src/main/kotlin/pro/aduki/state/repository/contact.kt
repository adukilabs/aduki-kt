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
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact_

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
                box.query(Contact_.hex.equal(hex)).build().findFirst()

            // Through the keyed blind index when the vault is attached; otherwise
            // a scan over the decrypted rows (every contact is read and opened).
            override fun byEmail(email: String): ContactEntity? {
                val key = Sealing.index("email", email)
                if (key.isNotEmpty()) return box.query(Contact_.emailIndex.equal(key)).build().findFirst()
                val q = email.trim().lowercase()
                return if (q.isEmpty()) null else box.all.firstOrNull { it.email.trim().lowercase() == q }
            }

            override fun byPhone(phone: String): ContactEntity? {
                val key = Sealing.index("phone", phone)
                if (key.isNotEmpty()) return box.query(Contact_.phoneIndex.equal(key)).build().findFirst()
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
                        list.filter {
                            it.name.lowercase().contains(q) ||
                                    it.email.lowercase().contains(q) ||
                                    it.phone.lowercase().contains(q)
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

