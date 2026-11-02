package pro.aduki.core.thread

import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Person
import pro.aduki.core.models.Reply
import pro.aduki.core.models.Security
import java.time.Instant

/**
 * Entry is one email of a thread as fetched (JMAP `Email/get`) or read from
 * the local store: the input of [Threads.build].
 *
 * Message ids are taken with or without angle brackets. [reply] is the
 * server's `aduki:reply` (with [Reply.source] `Server`), or null; [text],
 * [html] and [quoteStart] the body, needed only when [reply] is missing or
 * has low confidence.
 */
data class Entry(
    val hex: String = "",
    val messageId: String? = null,
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val subject: String = "",
    val from: Person? = null,
    val to: List<Person> = emptyList(),
    val cc: List<Person> = emptyList(),
    val replyTo: List<Person> = emptyList(),
    val sentAt: Instant? = null,
    val receivedAt: Instant? = null,
    /** JMAP keywords (`$seen`, `$flagged`, `$draft`, ...). */
    val keywords: Set<String> = emptySet(),
    val mailboxes: Set<String> = emptySet(),
    /** The copy sits in the Sent mailbox: another copy wins for security facts. */
    val sent: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val reply: Reply? = null,
    val security: Security? = null,
    val text: String = "",
    val html: String = "",
    val quoteStart: Int? = null
) {
    /** Not marked `$seen`. */
    fun unread(): Boolean = keywords.none { it.equals("\$seen", ignoreCase = true) }
}
