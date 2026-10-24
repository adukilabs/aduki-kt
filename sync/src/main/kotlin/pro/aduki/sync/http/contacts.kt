package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.ContactRow
import pro.aduki.core.vcard.Vcard
import pro.aduki.net.http.Contacts
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Outbox
import pro.aduki.sync.engine.ContactDelta
import pro.aduki.sync.engine.ContactTransport
import pro.aduki.sync.outbox.Book
import pro.aduki.sync.outbox.Dispatcher
import pro.aduki.sync.outbox.Rejected
import java.security.MessageDigest

/**
 * HttpContactTransport syncs the address book over `GET /user/contacts`.
 *
 * The server has no incremental REST route for contacts (its JMAP
 * `Contact/changes` uses ids this client does not hold), so every sync reads
 * the whole list and reconciles it: the delta says `full`, and the engine
 * removes local contacts the server no longer lists. To spare the database,
 * the "ctag" is a digest of every `hex:etag:groups` line (a group change
 * leaves the etag as it is); when it matches the stored one the delta is
 * empty.
 *
 * Rows carry the vCard: company, notes and photo are read from it, and all
 * e-mail addresses and phones are kept. The server answers the list in page
 * mode (`page`, `pages`) and ignores the `after` cursor, so both are followed.
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
        var number: Int? = null
        repeat(maxPages) {
            val listing = api.list(after, pageSize, number)
            listing.items.forEach { rows[it.hex] = it }
            val next = listing.next
            val (current, pages) = listing.page to listing.pages
            when {
                !next.isNullOrBlank() -> {
                    if (!seen.add(next)) throw AdukiException.Protocol("Contacts cursor repeated: $next")
                    after = next
                }
                current != null && pages != null && current < pages && listing.items.isNotEmpty() -> number = current + 1
                else -> return rows.values.toList()
            }
        }
        throw AdukiException.Protocol("Contacts list longer than $maxPages pages")
    }

    companion object {
        /** SHA-256 over the sorted `hex:etag:groups` lines. */
        fun digest(rows: List<ContactRow>): String {
            val md = MessageDigest.getInstance("SHA-256")
            rows.map { "${it.hex}:${it.etag}:${it.groups.joinToString(",")}\n" }.sorted().forEach { md.update(it.toByteArray()) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** A list row as a local contact; the etag is kept in `ctag`. */
        fun contact(row: ContactRow): ContactEntity {
            // The card's own values (without `mailto:`/`tel:`) when it parses.
            val card = runCatching { Vcard.parse(row.vcard) }.getOrNull()
            val emails = card?.emails ?: row.emails
            val phones = card?.phones ?: row.phones
            return ContactEntity(
                hex = row.hex,
                name = row.name ?: card?.name ?: "",
                email = emails.firstOrNull() ?: "",
                phone = phones.firstOrNull() ?: "",
                company = card?.company ?: "",
                vcard = row.vcard,
                ctag = row.etag,
                updated = row.updated.takeIf { it > 0 } ?: row.created,
                uid = row.uid,
                emails = emails,
                phones = phones,
                groups = row.groups,
                notes = card?.notes ?: "",
                photo = card?.photo ?: ""
            )
        }
    }
}

/**
 * ContactDispatcher sends the address book actions queued by [Book]:
 *
 * - `contact.create`: `POST /user/contacts`; the reply's row replaces the
 *   local placeholder through [created].
 * - `contact.update`: `PATCH /user/contacts/{hex}/vcard`, then `.../groups`.
 * - `contact.groups`: `PATCH /user/contacts/{hex}/groups`.
 * - `contact.rename` / `contact.dissolve`: the proposed group-wide routes
 *   (`PATCH` / `DELETE /user/contacts/group/{group}`, see `Contacts.rename`);
 *   while the server answers them with 405 or 404, the members are read
 *   from `GET /user/contacts/group/{group}` and each one's groups are set.
 * - `contact.delete`: `DELETE /user/contacts/{hex}`; a 404 completes it.
 *
 * Writes carry a stable `Idempotency-Key` per outbox entry, so a retry after
 * a lost reply does not create a second contact. Anything else goes to
 * [fallback], so dispatchers chain (`HttpDispatcher(..., fallback =
 * ContactDispatcher(..., fallback = next))`). A 4xx the client cannot fix by
 * retrying (not 408, 409 or 429) is thrown as [Rejected].
 */
class ContactDispatcher(
    private val api: Contacts,
    /** Gives the local placeholder (old hex) the server's row. */
    private val created: (old: String, row: ContactRow) -> Unit = { _, _ -> },
    private val fallback: Dispatcher? = null
) : Dispatcher {

    override suspend fun dispatch(action: Outbox) {
        if (!action.action.startsWith(Book.PREFIX)) {
            val next = fallback ?: throw Rejected("No dispatcher for outbox action '${action.action}'")
            next.dispatch(action)
            return
        }
        withContext(Dispatchers.IO) {
            try {
                send(action)
            } catch (e: AdukiException.Network) {
                val code = e.code ?: throw e
                if (code == 404 && action.action == Book.DELETE) return@withContext
                if (code in 400..499 && code !in RETRYABLE) {
                    throw Rejected("Aduki rejected '${action.action}' (HTTP $code)", code, e)
                }
                throw e
            } catch (e: JSONException) {
                throw Rejected("Outbox ${action.action} ${action.id} has a bad payload", cause = e)
            } catch (e: IllegalArgumentException) {
                throw Rejected("Outbox ${action.action} ${action.id}: ${e.message}", cause = e)
            }
        }
    }

    private fun send(action: Outbox) {
        val obj = JSONObject(String(action.payload, Charsets.UTF_8).ifBlank { "{}" })
        val key = HttpDispatcher.idempotencyKey(action)
        val hex = action.hex
        when (action.action) {
            Book.CREATE -> created(hex, api.create(obj.getString("vcard"), strings(obj.optJSONArray("groups")), key))
            Book.UPDATE -> {
                api.replace(hex, obj.getString("vcard"), key)
                api.groups(hex, strings(obj.optJSONArray("groups")), key)
            }
            Book.GROUPS -> api.groups(hex, strings(obj.optJSONArray("groups")), key)
            Book.DELETE -> api.delete(hex)
            Book.RENAME -> {
                val (group, name) = obj.getString("group") to obj.getString("name")
                wide(group, key, { api.rename(group, name, key) }) { groups -> groups.map { if (it == group) name else it }.distinct() }
            }
            Book.DISSOLVE -> {
                val group = obj.getString("group")
                wide(group, key, { api.dissolve(group) }) { groups -> groups - group }
            }
            else -> throw Rejected("Unknown contact action '${action.action}'")
        }
    }

    // The one-call route when the server has it, else member by member.
    private fun wide(group: String, key: String, call: () -> Unit, edit: (List<String>) -> List<String>) {
        try {
            call()
            return
        } catch (e: AdukiException.Network) {
            if (e.code != 404 && e.code != 405) throw e
        }
        var page = 1
        while (true) {
            val listing = api.members(group, page = page)
            for (row in listing.items) api.groups(row.hex, edit(row.groups), key)
            val pages = listing.pages ?: 1
            if (listing.items.isEmpty() || page >= pages) return
            page++
        }
    }

    private companion object {
        val RETRYABLE = setOf(408, 409, 429)

        fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
    }
}
