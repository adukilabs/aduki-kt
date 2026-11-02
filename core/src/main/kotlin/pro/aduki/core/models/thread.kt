package pro.aduki.core.models

import java.time.Instant

/** Person is a participant of a conversation: the same `{name, email}` as [Address]. */
typealias Person = Address

/**
 * Conversation is a mail thread as the thread reader shows it: its messages
 * as a reply tree, each reduced to its own words, with how each was
 * delivered. `Mail.conversation(threadId)` builds it.
 *
 * [messages] are in reading order: a depth-first walk of the reply tree,
 * siblings by time. Every message of the thread is there exactly once
 * (copies with the same Message-ID are merged), plus a placeholder for each
 * referenced parent that is not in the account ([ThreadMessage.placeholder]).
 */
data class Conversation(
    val threadId: String = "",
    /** The subject of the first message without `Re:`/`Fwd:` prefixes. */
    val subject: String = "",
    /** Senders first, then recipients, each address once. */
    val participants: List<Person> = emptyList(),
    val messages: List<ThreadMessage> = emptyList(),
    val security: SecuritySummary = SecuritySummary(),
    /** Built from the local store only (the server could not be reached). */
    val offline: Boolean = false
)

/**
 * ThreadMessage is one node of a [Conversation].
 *
 * A placeholder stands for a message the thread refers to but this account
 * does not have ("Earlier message not in your mailbox"): its [hex] starts
 * with [PLACEHOLDER], and only [messageId], [parent], [depth] and [time] are set.
 */
data class ThreadMessage(
    /** The message hex (JMAP id), or [PLACEHOLDER] + Message-ID for a placeholder. */
    val hex: String = "",
    /** The Message-ID without angle brackets, or null when the message has none. */
    val messageId: String? = null,
    /** The [hex] of the parent node, or null for a root. */
    val parent: String? = null,
    /** 0 for a root; capped at [DEPTH]. */
    val depth: Int = 0,
    val from: Person = Person(),
    val to: List<Person> = emptyList(),
    val cc: List<Person> = emptyList(),
    val replyTo: List<Person> = emptyList(),
    val subject: String = "",
    /** The `Date:` header, or null when missing or unreadable. */
    val sentAt: Instant? = null,
    val receivedAt: Instant? = null,
    /** The time the message is ordered by: [sentAt], or [receivedAt] when [sentAt] is missing or more than 48 h ahead. */
    val time: Instant? = null,
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val draft: Boolean = false,
    /** JMAP keywords, as sent by the server (`$seen`, `$flagged`, ...). */
    val keywords: Set<String> = emptySet(),
    /** Mailbox hexes the message is in (every copy). */
    val mailboxes: Set<String> = emptySet(),
    /** Hexes of every copy merged into this node (the sent and the received copy), [hex] first. */
    val copies: List<String> = emptyList(),
    val reply: Reply = Reply(),
    val attachments: List<Attachment> = emptyList(),
    /** How the message was delivered (`aduki:security`), or null when the server does not say. */
    val security: Security? = null,
    val placeholder: Boolean = false
) {
    companion object {
        /** Hex prefix of a placeholder node. */
        const val PLACEHOLDER = "missing:"

        /** The deepest [depth] reported. */
        const val DEPTH = 32
    }
}

/**
 * Reply is a message reduced to its own words: [text] (and [html] when the
 * message has HTML) without the quoted history and the signature, which are
 * kept in [quoted] and [signature] so nothing is lost.
 *
 * With [Confidence.Low] nothing was cut: [text] and [html] are the whole body.
 */
data class Reply(
    val text: String = "",
    /** The own words as HTML, or null (no HTML part, or it could not be cut). Sanitised only when the server sanitises bodies. */
    val html: String? = null,
    /** The quoted history that was cut, as text, or null. */
    val quoted: String? = null,
    /** The [ThreadMessage.hex] of the quoted message when it is in the thread, or null. */
    val quotedFrom: String? = null,
    /** The Message-ID (no angle brackets) of the quoted message, when known. */
    val quotedId: String? = null,
    /** The quoted author's name, when known. */
    val author: String? = null,
    /** Where the quote starts in the whole text (a [String] index), when known. */
    val quoteStart: Int? = null,
    val signature: String? = null,
    /** A forwarded message, shown as a card; never treated as a quote. */
    val forwarded: Forwarded? = null,
    val confidence: Confidence = Confidence.Low,
    /** Which step decided the cut. */
    val source: ReplySource = ReplySource.None
)

/** Forwarded is a message forwarded inside another. Fields the forward does not show are null. */
data class Forwarded(
    val from: String? = null,
    val date: String? = null,
    val subject: String? = null,
    val text: String = ""
)

/**
 * Confidence of a [Reply] cut: [High] an explicit marker (an HTML quote
 * container, an attribution line, an Outlook header), [Medium] a heuristic
 * match, [Low] nothing cut (the whole body).
 */
enum class Confidence { High, Medium, Low }

/**
 * ReplySource is the step that decided a [Reply]: [Server] the server's
 * `aduki:reply` or its quote start, [Marker] an HTML quote marker,
 * [Heuristic] a text pattern, [Diff] the text of an earlier message found at
 * the end, [None] nothing found.
 */
enum class ReplySource { Server, Marker, Heuristic, Diff, None }

/**
 * Security is how a message reached the server (`aduki:security`). A fact
 * the server does not know is null; it is never guessed.
 */
data class Security(
    val transport: Transport? = null,
    val spf: AuthResult? = null,
    val dkim: List<AuthResult>? = null,
    val dmarc: AuthResult? = null,
    /** The domain of the passing DKIM signature aligned with the sender. */
    val signedBy: String? = null,
    /** The domain that handed the message over. */
    val mailedBy: String? = null,
    /** Mail between two accounts of the same server. */
    val internal: Boolean = false
)

/** Transport is the last SMTP hop into the server: TLS, its version and cipher, and the peer host. */
data class Transport(
    val tls: Boolean = false,
    val version: String? = null,
    val cipher: String? = null,
    val from: String? = null
)

/**
 * AuthResult is one SPF, DKIM or DMARC check: [result] as the server wrote it
 * (`pass`, `fail`, `softfail`, `neutral`, `none`, `temperror`, `permerror`),
 * the [domain], and the DKIM [selector] or DMARC [policy] when they apply.
 */
data class AuthResult(
    val result: String = "",
    val domain: String? = null,
    val selector: String? = null,
    val policy: String? = null
) {
    /** The check passed. */
    fun pass(): Boolean = result.equals("pass", ignoreCase = true)
}

/**
 * SecuritySummary sums up the transport of a conversation for its header
 * chip.
 */
data class SecuritySummary(
    /** At least one message carries security facts (the server supports them). */
    val available: Boolean = false,
    /** Every message whose transport is known arrived over TLS (or within the server), and at least one is known. */
    val encrypted: Boolean = false,
    /** The hex of the first message known to have arrived without TLS, or null. */
    val plain: String? = null
)
