package pro.aduki.core.models

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
        for (value in emails + phones + groups) {
            require(value.isNotBlank()) { "blank e-mail, phone or group" }
            require('\n' !in value && '\r' !in value) { "line break in '$value'" }
        }
    }

    /** Whether the fields (not the source vCard) are the same as [other]'s. */
    fun same(other: Card): Boolean = copy(uid = "", vcard = "") == other.copy(uid = "", vcard = "")
}
