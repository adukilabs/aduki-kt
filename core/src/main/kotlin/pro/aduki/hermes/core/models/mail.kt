package pro.aduki.hermes.core.models

/**
 * Address is one parsed email address (`{name, email}` on the wire).
 */
data class Address(
    val name: String? = null,
    val email: String = ""
)

/**
 * MessageRow is one message in a list response (inbox, folder, thread,
 * search) or one `created` entry of a changes response.
 */
data class MessageRow(
    val hex: String = "",
    val uid: Long = 0,
    val subject: String = "",
    val sender: String = "",
    val size: Long = 0,
    /** IMAP flags as sent by the server, e.g. `\Seen`, `\Flagged`, `$Junk`. */
    val flags: List<String> = emptyList(),
    val thread: String? = null,
    val spam: Double? = null,
    /** Arrival time, epoch milliseconds (UTC). */
    val received: Long = 0,
    val preview: String = "",
    val from: Address? = null,
    val to: List<Address> = emptyList(),
    val hasAttachment: Boolean = false,
    val mailbox: String = "",
    val mailboxName: String = "",
    /** Only set in changes responses; 0 otherwise. */
    val modseq: Long = 0
)

/**
 * FlagUpdate reports new flags on a message the client already has.
 */
data class FlagUpdate(
    val hex: String = "",
    val uid: Long = 0,
    val flags: List<String> = emptyList(),
    val modseq: Long = 0
)

/**
 * MailChanges is what changed in a mailbox since a client's last sync
 * (`GET /user/mail/changes`).
 */
data class MailChanges(
    val mailbox: String = "",
    val uidvalidity: Long = 0,
    /** Resume from here on the next call. */
    val modseq: Long = 0,
    /** The client's uidvalidity is stale: drop the local copy and resync from 0. */
    val reset: Boolean = false,
    /** More changes wait past [modseq]; call again with it. */
    val more: Boolean = false,
    val created: List<MessageRow> = emptyList(),
    val updated: List<FlagUpdate> = emptyList(),
    val vanished: List<Long> = emptyList()
)

/**
 * MailboxRow is one mailbox in `GET /user/mailbox`.
 */
data class MailboxRow(
    val hex: String = "",
    val name: String = "",
    val delimiter: String = ".",
    val flags: List<String> = emptyList(),
    val uidvalidity: Long = 0,
    val uidnext: Long = 0,
    val messages: Long = 0,
    val unread: Long = 0
)

/**
 * Listing is one page of a list response.
 */
data class Listing<T>(
    val items: List<T> = emptyList(),
    val total: Long = 0,
    /** Cursor for the next page (cursor mode). */
    val next: String? = null,
    /** Page number and count (page mode). */
    val page: Int? = null,
    val pages: Int? = null
)

/**
 * Moved is the result of moving a message: same id, new mailbox and UID.
 */
data class Moved(
    val hex: String = "",
    val mailbox: String = "",
    val uid: Long = 0,
    val modseq: Long = 0
)
