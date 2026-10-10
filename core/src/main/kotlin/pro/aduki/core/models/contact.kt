package pro.aduki.core.models

/**
 * ContactRow is one row of `GET /user/contacts`: the list view of a contact.
 * It carries no vCard; the server has no route that returns one (there is no
 * `GET /user/contacts/{hex}`).
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
    val created: Long = 0
)
