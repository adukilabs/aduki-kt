package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.ContactRow
import pro.aduki.core.models.GroupRow
import pro.aduki.core.vcard.Vcard
import pro.aduki.net.http.Contacts
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Outbox
import pro.aduki.sync.engine.ContactDelta
import pro.aduki.sync.engine.ContactTransport
import pro.aduki.sync.outbox.Book
import pro.aduki.sync.outbox.Dispatcher
import pro.aduki.sync.outbox.Photos
import pro.aduki.sync.outbox.Rejected
import java.security.MessageDigest

/**
 * HttpContactTransport syncs the address book over `GET /user/contacts/sync`.
 *
 * With no stored cursor it reads a full listing, page by page; the last
 * page's `token` is kept, and the next sync reads only the cards changed and
 * removed after it. Each page goes to the engine as it arrives, so a first
 * sync shows progress and a warm one reads a page or less. A token the
 * server no longer knows (`410 sync.reset`) or cannot read (`422`) is dropped
 * and the listing starts over.
 *
 * A server without the route (`404`, `405`) is read the old way, every
 * sync ([fetch]): the whole `GET /user/contacts` list, reconciled as a full
 * listing. The "ctag" is then a digest of every `hex:etag` line (the etag
 * covers the groups too); when it matches the stored one the delta is empty.
 * That list is walked with the `after` cursor (`next`), or by page number
 * from a server that answers in page mode. Once a server answered 404 or
 * 405, this transport keeps to the old way.
 *
 * Every sync also reads the group list (`GET /user/contacts/groups`), which
 * names groups without members.
 *
 * Rows carry the vCard: company, notes and an inline photo are read from
 * it, and all e-mail addresses and phones are kept. A photo kept as a blob
 * is named by `photoHex`.
 */
class HttpContactTransport(
    private val api: Contacts,
    private val pageSize: Int = 200,
    private val maxPages: Int = 1000
) : ContactTransport {

    // The server has no `/user/contacts/sync`.
    @Volatile private var legacy = false

    override suspend fun pages(tenant: String, ctag: String, apply: suspend (ContactDelta) -> Unit) {
        if (!legacy && changes(ctag, apply)) return
        apply(fetch(tenant, ctag))
    }

    // False when the server has no sync route: nothing was applied.
    private suspend fun changes(ctag: String, apply: suspend (ContactDelta) -> Unit): Boolean {
        var since = ctag.ifBlank { null }
        var reset = false
        var first = true
        repeat(maxPages) {
            val page = try {
                withContext(Dispatchers.IO) { api.changes(since, pageSize.coerceIn(1, 500)) }
            } catch (e: AdukiException.Network) {
                when (e.code) {
                    404, 405 -> if (first) {
                        legacy = true
                        return false
                    } else throw e
                    // The cursor is gone: a full listing, once.
                    410, 422 -> if (since != null && !reset) {
                        reset = true
                        since = null
                        return@repeat
                    } else throw e
                    else -> throw e
                }
            }
            first = false
            if (page.more && page.token == since) throw AdukiException.Protocol("Contacts sync token repeated")
            val groups = if (page.more) null else withContext(Dispatchers.IO) { groups() }
            apply(
                ContactDelta(
                    changed = page.items.map(::contact),
                    removed = page.removed,
                    ctag = page.token,
                    full = page.full,
                    groups = groups,
                    more = page.more,
                    total = page.total
                )
            )
            if (!page.more) return true
            since = page.token
        }
        throw AdukiException.Protocol("Contacts sync longer than $maxPages pages")
    }

    /** The old way: the whole list, as one full delta (empty when its digest is [ctag]). */
    override suspend fun fetch(tenant: String, ctag: String): ContactDelta =
        withContext(Dispatchers.IO) {
            val rows = all()
            val current = digest(rows)
            val groups = groups()
            if (current == ctag) {
                ContactDelta(ctag = current, groups = groups)
            } else {
                ContactDelta(changed = rows.map(::contact), ctag = current, full = true, groups = groups, total = rows.size)
            }
        }

    // Null from a server without the group list.
    private fun groups(): List<GroupRow>? = try {
        api.groups()
    } catch (e: AdukiException.Network) {
        if (e.code == 404 || e.code == 405) null else throw e
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
        /** SHA-256 over the sorted `hex:etag` lines. */
        fun digest(rows: List<ContactRow>): String {
            val md = MessageDigest.getInstance("SHA-256")
            rows.map { "${it.hex}:${it.etag}\n" }.sorted().forEach { md.update(it.toByteArray()) }
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
                photo = card?.photo ?: "",
                collected = row.collected || (card?.collected ?: false),
                photoHex = row.photoHex,
                photoUpdated = row.photoUpdated
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
 * - `contact.group`: `POST /user/contacts/groups` (an existing group is fine).
 * - `contact.rename` / `contact.dissolve`: `PATCH` / `DELETE
 *   /user/contacts/group/{group}`, one call for every member.
 * - `contact.merge`: `POST /user/contacts/{hex}/merge` `{others, vcard}`,
 *   one server transaction; the reply's row updates the local contact. A
 *   404 (one of them is gone) falls back to an update of the contact and a
 *   delete of each other one.
 * - `contact.delete`: `DELETE /user/contacts/{hex}`; a 404 completes it.
 * - `contact.photo`: `PUT /user/contacts/{hex}/photo` with the spooled
 *   bytes ([photos]); the photo is cached under the blob id of the reply,
 *   [photographed] gives the contact that id, and the spool file goes. A
 *   404 (the contact is gone, or a server without photo routes) is
 *   [Rejected], like a refused photo (413, 415, 422).
 * - `contact.unphoto`: `DELETE /user/contacts/{hex}/photo`; a 404 completes it.
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
    private val fallback: Dispatcher? = null,
    /** The spool and cache of photos; without it `contact.photo` is [Rejected]. */
    private val photos: Photos? = null,
    /** Gives the contact the server's photo (`Book.photographed`): hex, spool (null on removal), reply. */
    private val photographed: (hex: String, spool: String?, row: ContactRow) -> Unit = { _, _, _ -> }
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
                if (code == 404 && (action.action == Book.DELETE || action.action == Book.UNPHOTO)) return@withContext
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
            Book.GROUP -> api.createGroup(obj.getString("name"), key)
            Book.RENAME -> api.rename(obj.getString("group"), obj.getString("name"), key)
            Book.DISSOLVE -> api.dissolve(obj.getString("group"))
            Book.MERGE -> merge(hex, obj, key)
            Book.PHOTO -> photo(hex, obj, key)
            Book.UNPHOTO -> {
                photographed(hex, null, api.removePhoto(hex, key))
                photos?.sent()
            }
            else -> throw Rejected("Unknown contact action '${action.action}'")
        }
    }

    private fun photo(hex: String, obj: JSONObject, key: String) {
        val photos = photos ?: throw Rejected("No photo storage for contact.photo")
        val spool = obj.getString("spool")
        val bytes = photos.spooled(spool) ?: throw Rejected("The photo queued for $hex is gone")
        val type = obj.optString("type").ifBlank { Photos.type(bytes) ?: "image/jpeg" }
        val row = api.setPhoto(hex, bytes, type, key)
        // The device has these bytes already: no download for its own photo.
        runCatching { photos.cache(row.photoHex, bytes) }
        photographed(hex, spool, row)
        photos.release(spool)
        photos.sent()
    }

    private fun merge(hex: String, obj: JSONObject, key: String) {
        val others = strings(obj.optJSONArray("others"))
        val vcard = obj.getString("vcard")
        try {
            created(hex, api.merge(hex, others, vcard, key))
        } catch (e: AdukiException.Network) {
            if (e.code != 404) throw e
            // One of them is gone (a 404 changes nothing): what is left, one call each.
            api.replace(hex, vcard, key)
            api.groups(hex, strings(obj.optJSONArray("groups")), key)
            for (other in others) {
                try {
                    api.delete(other)
                } catch (gone: AdukiException.Network) {
                    if (gone.code != 404) throw gone
                }
            }
        }
    }

    private companion object {
        val RETRYABLE = setOf(408, 409, 429)

        fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
    }
}
