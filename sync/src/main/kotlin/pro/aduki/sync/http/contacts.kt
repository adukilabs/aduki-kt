package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.ContactRow
import pro.aduki.net.http.Contacts
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.sync.engine.ContactDelta
import pro.aduki.sync.engine.ContactTransport
import java.security.MessageDigest

/**
 * HttpContactTransport syncs the address book over `GET /user/contacts`.
 *
 * The server has no incremental REST route for contacts (its JMAP
 * `Contact/changes` uses ids this client does not hold), so every sync reads
 * the whole list and reconciles it: the delta says `full`, and the engine
 * removes local contacts the server no longer lists. To spare the database,
 * the "ctag" is a digest of every `hex:etag` pair; when it matches the stored
 * one the delta is empty.
 *
 * Limits of the list route: rows carry no vCard, one e-mail address and one
 * phone are kept (the first), and there is no server-side modification time,
 * so `updated` is the creation time.
 */
class HttpContactTransport(
    private val api: Contacts,
    private val pageSize: Int = 200,
    private val maxPages: Int = 1000
) : ContactTransport {

    override suspend fun fetch(tenant: String, ctag: String): ContactDelta =
        withContext(Dispatchers.IO) {
            val rows = all()
            val current = digest(rows)
            if (current == ctag) {
                ContactDelta(ctag = current)
            } else {
                ContactDelta(changed = rows.map(::contact), ctag = current, full = true)
            }
        }

    private fun all(): List<ContactRow> {
        val rows = LinkedHashMap<String, ContactRow>()
        val seen = HashSet<String>()
        var after: String? = null
        repeat(maxPages) {
            val page = api.list(after, pageSize)
            page.items.forEach { rows[it.hex] = it }
            val next = page.next
            if (next.isNullOrBlank()) return rows.values.toList()
            if (!seen.add(next)) throw AdukiException.Protocol("Contacts cursor repeated: $next")
            after = next
        }
        throw AdukiException.Protocol("Contacts list longer than $maxPages pages")
    }

    companion object {
        /** SHA-256 over the sorted `hex:etag` lines. */
        fun digest(rows: List<ContactRow>): String {
            val md = MessageDigest.getInstance("SHA-256")
            rows.map { "${it.hex}:${it.etag}\n" }.sorted().forEach { md.update(it.toByteArray()) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** A list row as a local contact; the etag is kept in `ctag`. */
        fun contact(row: ContactRow): ContactEntity = ContactEntity(
            hex = row.hex,
            name = row.name ?: "",
            email = row.emails.firstOrNull() ?: "",
            phone = row.phones.firstOrNull() ?: "",
            ctag = row.etag,
            updated = row.created
        )
    }
}
