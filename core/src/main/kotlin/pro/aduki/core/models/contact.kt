package pro.aduki.core.models

/**
 * Postal address component modeled after RFC 6350 §6.3.1 (ADR).
 */
data class Postal(
    val street: String = "",
    val locality: String = "",
    val region: String = "",
    val code: String = "",
    val country: String = "",
    val pobox: String = "",
    val ext: String = "",
    val type: String = "home"
) {
    /** Formatted single-line display representation. */
    fun formatted(): String = listOf(street, locality, region, code, country)
        .filter { it.isNotBlank() }
        .joinToString(", ")
}

/**
 * Labeled web link modeled after RFC 6350 §6.7.8 (URL).
 */
data class Link(
    val url: String = "",
    val type: String = "home"
)

/**
 * ContactRow is one row of `GET /user/contacts` (or the reply to
 * `POST /user/contacts`): a contact with its vCard.
 */
data class ContactRow(
    val hex: String,
    /** Changes whenever the contact changes. */
    val etag: String = "",
    val name: String? = null,
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val addresses: List<Postal> = emptyList(),
    val title: String = "",
    val birthday: String = "",
    val urls: List<Link> = emptyList(),
    val nickname: String = "",
    /** Whether this contact was automatically harvested (e.g. from sent mail/invites). */
    val collected: Boolean = false,
    /** Epoch milliseconds, 0 when absent. */
    val created: Long = 0,
    /** The vCard `UID`. */
    val uid: String = "",
    val vcard: String = "",
    /** Epoch milliseconds of the last change, 0 when absent. */
    val updated: Long = 0,
    /**
     * The blob id of the contact's photo (`photo.hex`, 16 hex digits), "" for
     * none or a server without photo blobs. A card whose photo is a blob has
     * no `PHOTO` line in [vcard]; read it with `Contacts.photo`.
     */
    val photoHex: String = "",
    /** Epoch milliseconds the photo was last set (`photo.updated`), 0 when none. */
    val photoUpdated: Long = 0
)

/**
 * ContactChanges is one page of `GET /user/contacts/sync`: the cards changed
 * since a token, or one page of a full listing.
 */
data class ContactChanges(
    /** Cards created or changed (every card of the page in a full listing). */
    val items: List<ContactRow> = emptyList(),
    /** Hexes of cards deleted since the token; empty in a full listing. */
    val removed: List<String> = emptyList(),
    /** Opaque: pass it as `since` for the next page, or the next sync when [more] is false. */
    val token: String,
    /** Another page follows: ask again with [token]. */
    val more: Boolean = false,
    /**
     * This page belongs to a full listing (no or an expired `since`): once
     * [more] is false, local cards not seen on any of its pages are gone.
     */
    val full: Boolean = false,
    /** How many cards the full listing has; on its first page only. */
    val total: Int? = null
)

/**
 * ContactPhoto is a contact's photo as `GET /user/contacts/{hex}/photo`
 * answers it. [etag] is the blob hex; an empty [bytes] means the server
 * answered `304 Not Modified` to the `If-None-Match` sent.
 */
class ContactPhoto(
    val bytes: ByteArray,
    val etag: String,
    /** The media type (`image/jpeg`, `image/png`, `image/webp`), "" on a 304. */
    val type: String
) {
    /** Whether the server answered `304 Not Modified`: keep the copy held. */
    val unchanged: Boolean get() = bytes.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is ContactPhoto && etag == other.etag && type == other.type && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * etag.hashCode() + type.hashCode()) + bytes.contentHashCode()

    override fun toString(): String = "ContactPhoto(etag=$etag, type=$type, bytes=${bytes.size})"
}

/**
 * SyncProgress is what the address book sync is doing, for a progress bar
 * and a status line.
 */
sealed class SyncProgress {
    /** No sync has run yet in this process. */
    object Idle : SyncProgress() {
        override fun toString(): String = "Idle"
    }

    /**
     * A sync is applying pages: [done] cards so far; [total] is known on a
     * full listing from a server with `/user/contacts/sync`, null otherwise
     * (show an indeterminate bar).
     */
    data class Running(val done: Int = 0, val total: Int? = null) : SyncProgress()

    /** The last sync failed with [message] at [at] (epoch ms); pages applied before it are kept. */
    data class Failed(val message: String, val at: Long) : SyncProgress()

    /** The last sync finished at [at] (epoch ms) and stored or removed [changed] cards. */
    data class Done(val at: Long, val changed: Int) : SyncProgress()
}

/**
 * PhotoProgress counts contact photo writes (`setPhoto`, `removePhoto`)
 * since the client was built: [queued] are waiting in the outbox, [done]
 * reached the server, [failed] were refused for good (too big, wrong type,
 * too many pixels) and can be retried or discarded from the outbox.
 */
data class PhotoProgress(
    val queued: Int = 0,
    val done: Int = 0,
    val failed: Int = 0
)

/**
 * Card is what a client writes to a contact: the fields an editor shows, and
 * the vCard they came from. Properties not modelled here (addresses,
 * birthdays, `X-` properties, ...) are kept from [vcard] when it is
 * rewritten.
 */
data class Card(
    val name: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val company: String = "",
    val notes: String = "",
    /** A `data:` URI (`data:image/jpeg;base64,...`) or a URL; "" for none. */
    val photo: String = "",
    /** Group names (the server's group tags). */
    val groups: List<String> = emptyList(),
    val addresses: List<Postal> = emptyList(),
    val title: String = "",
    val birthday: String = "",
    val urls: List<Link> = emptyList(),
    val nickname: String = "",
    /** True if this contact was auto-collected rather than added manually. */
    val collected: Boolean = false,
    /** The vCard `UID`; one is assigned on create when blank. */
    val uid: String = "",
    /** The source vCard, or "". */
    val vcard: String = ""
) {
    /** Throws [IllegalArgumentException] for what the server would refuse. */
    fun validate() {
        require(name.isNotBlank()) { "name is required" }
        require(name.length <= 200) { "name is longer than 200 characters" }
        require(emails.size <= 100) { "more than 100 e-mail addresses" }
        require(phones.size <= 100) { "more than 100 phone numbers" }
        require(groups.size <= 50) { "more than 50 groups" }
        require(addresses.size <= 50) { "more than 50 postal addresses" }
        require(urls.size <= 50) { "more than 50 links" }
        for (value in emails + phones + groups) {
            require(value.isNotBlank()) { "blank e-mail, phone or group" }
            require('\n' !in value && '\r' !in value) { "line break in '$value'" }
        }
    }

    /** Whether the fields (not the source vCard) are the same as [other]'s. */
    fun same(other: Card): Boolean = copy(uid = "", vcard = "") == other.copy(uid = "", vcard = "")
}

/**
 * GroupRow is one item of `GET /user/contacts/groups`: a group name and how
 * many contacts carry it (0 for a group created without members).
 */
data class GroupRow(
    val name: String,
    val count: Int = 0
)

/**
 * ImportReport is the reply to `POST /user/contacts/import`: what the
 * server did with each card of the file.
 */
data class ImportReport(
    val created: Int = 0,
    val updated: Int = 0,
    /** Cards already stored exactly as sent. */
    val skipped: Int = 0,
    /** The cards not stored: their position in the file (from 0) and why. */
    val errors: List<Pair<Int, String>> = emptyList()
)
