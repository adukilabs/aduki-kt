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
import pro.aduki.store.entities.Contact_

/**
 * ContactSource abstracts reactive entity streams for contacts.
 */
interface ContactSource {
    fun contacts(): Flow<List<ContactEntity>>
    fun get(hex: String): ContactEntity?
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
                val query = box.query().order(Contact_.name).build()
                val sub = query.subscribe().observer { data ->
                    trySend(data)
                }
                awaitClose { sub.cancel() }
            }

            override fun get(hex: String): ContactEntity? =
                box.query(Contact_.hex.equal(hex)).build().findFirst()
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
}

typealias ContactRepository = Contact

