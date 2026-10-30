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
    val updated: Long = 0
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
