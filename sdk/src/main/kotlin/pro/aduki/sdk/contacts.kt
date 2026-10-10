package pro.aduki.sdk

import kotlinx.coroutines.flow.StateFlow
import pro.aduki.store.entities.Contact
import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.state.repository.Contact as ContactRepo

/**
 * Contacts service providing high-level operations for address book.
 */
class Contacts internal constructor(
    private val client: Aduki,
    private val repo: ContactRepo? = null,
    private val engine: ContactEngine? = null
) {

    /**
     * Performs incremental delta sync for address book.
     */
    suspend fun sync(tenant: String = ""): Boolean {
        val targetTenant = tenant.ifBlank { client.me()?.tenant ?: "" }
        return engine?.sync(targetTenant) ?: false
    }

    /**
     * Observes all contacts sorted by name as a hot StateFlow.
     */
    fun observe(): StateFlow<List<Contact>>? {
        return repo?.observe()
    }

    /**
     * Observes contacts filtered by search query as a hot StateFlow.
     */
    fun search(query: String): StateFlow<List<Contact>>? {
        return repo?.search(query)
    }

    /**
     * Synchronously looks up a contact by hex.
     */
    fun get(hex: String): Contact? {
        return repo?.get(hex)
    }
}
