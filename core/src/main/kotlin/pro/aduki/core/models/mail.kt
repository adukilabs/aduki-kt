package pro.aduki.core.models

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
    /** RFC 6154 special use (`inbox`, `sent`, `drafts`, `trash`, `junk`, `archive`), or null. */
    val role: String? = null,
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

/**
 * Attachment is one attachment of a message; [blob] downloads it.
 */
data class Attachment(
    val blob: String = "",
    val name: String = "",
    val type: String = "",
    val size: Long = 0,
    /** Content-ID of an inline part (an image the HTML refers to), or null. */
    val cid: String? = null,
    /** Shown inline in the body rather than listed. */
    val inline: Boolean = false
)

/**
 * Body is the full content of a message: its text and HTML parts (either
 * may be empty) and its attachments.
 */
data class Body(
    val hex: String = "",
    val text: String = "",
    val html: String = "",
    val attachments: List<Attachment> = emptyList()
)

/**
 * Upload is a file to attach: [bytes] in memory, or a [path] on disk that
 * must stay readable until the message is sent (better for large files:
 * the outbox then holds only the path).
 */
data class Upload(
    val name: String,
    val type: String = "application/octet-stream",
    val bytes: ByteArray? = null,
    val path: String? = null
) {
    // Arrays compare by content.
    override fun equals(other: Any?): Boolean {
        if (other !is Upload) return false
        val a = bytes
        val b = other.bytes
        val same = if (a == null || b == null) a == b else a.contentEquals(b)
        return same && name == other.name && type == other.type && path == other.path
    }

    override fun hashCode(): Int =
        listOf(name, type, path, bytes?.contentHashCode()).hashCode()
}

/**
 * Draft is an outgoing message. [reply] or [forward] is the hex of the
 * message answered or forwarded: the reply threads on the server
 * (`In-Reply-To`, `References`) and the original is marked answered or
 * forwarded once sent.
 */
data class Draft(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val text: String = "",
    val html: String? = null,
    /** An address the account may send as; the account's own when null. */
    val from: String? = null,
    /** Display name for [from]. */
    val name: String? = null,
    val attachments: List<Upload> = emptyList(),
    val reply: String? = null,
    val forward: String? = null
) {
    /** Throws [IllegalArgumentException] when the draft cannot be sent. */
    fun validate() {
        val all = to + cc + bcc
        require(all.isNotEmpty()) { "at least one recipient" }
        all.forEach { require('@' in it && it.isNotBlank()) { "not an address: $it" } }
        require(reply == null || forward == null) { "a draft replies or forwards, not both" }
        attachments.forEach { require(it.bytes != null || it.path != null) { "attachment ${it.name} has no bytes or path" } }
    }

    /** Sendable over the plain REST route: text only, no bcc, attachments, html or threading. */
    fun plain(): Boolean =
        bcc.isEmpty() && html == null && attachments.isEmpty() && reply == null && forward == null && name == null
}
