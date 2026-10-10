package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Message represents an email message stored in ObjectBox FlatBuffers binary format.
 *
 * Fields mirror the server's list row (the `/user/mail` list endpoints and `/user/mail/changes`).
 */
@Entity
data class Message(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var mailbox: String = "",
    @Index var uid: Long = 0,
    @Index var threadId: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var subject: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var fromName: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var fromEmail: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var to: String = "", // Comma-delimited recipients for FlatBuffers efficiency
    @Convert(converter = SealedText::class, dbType = String::class) var preview: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var blob: String = "",
    var size: Long = 0,
    @Index var flags: Int = 0, // Bitmask: SEEN=1, ANSWERED=2, FLAGGED=4, DELETED=8, DRAFT=16
    var keywords: String = "", // Other server flags/keywords, space-separated (e.g. "$Junk $Forwarded")
    var hasAttachment: Boolean = false,
    @Index var receivedAt: Long = 0, // Epoch millis (UTC) the server received it
    var sentAt: Long = 0, // Epoch millis from the Date: header, 0 if unknown
    var modseq: Long = 0,
    var created: Long = 0,
    var dirty: Boolean = false
) {
    companion object {
        const val SEEN = 1
        const val ANSWERED = 2
        const val FLAGGED = 4
        const val DELETED = 8
        const val DRAFT = 16

        private val SYSTEM = mapOf(
            "\\seen" to SEEN,
            "\\answered" to ANSWERED,
            "\\flagged" to FLAGGED,
            "\\deleted" to DELETED,
            "\\draft" to DRAFT
        )

        /** Server flag name of one bitmask bit, e.g. `\Seen` for [SEEN]. */
        fun flagName(bit: Int): String? = when (bit) {
            SEEN -> "\\Seen"
            ANSWERED -> "\\Answered"
            FLAGGED -> "\\Flagged"
            DELETED -> "\\Deleted"
            DRAFT -> "\\Draft"
            else -> null
        }

        /** The bitmask for the system flags in [flags] (case-insensitive). */
        fun bits(flags: List<String>): Int =
            flags.fold(0) { acc, f -> acc or (SYSTEM[f.lowercase()] ?: 0) }

        /** The flags in [flags] that are not system flags, space-separated. */
        fun keywords(flags: List<String>): String =
            flags.filter { SYSTEM[it.lowercase()] == null }.joinToString(" ")
    }

    fun seen(): Boolean = (flags and SEEN) != 0

    fun flagged(): Boolean = (flags and FLAGGED) != 0

    /** Marked `\Deleted` (awaiting expunge); hide it in the UI. */
    fun deleted(): Boolean = (flags and DELETED) != 0

    fun toggle(flag: Int) {
        flags = flags xor flag
        dirty = true
    }

    fun recipients(): List<String> {
        if (to.isBlank()) return emptyList()
        return to.split(",").map { it.trim() }
    }

    /** The sender for display: the name when known, else the address. */
    fun sender(): String = fromName.ifBlank { fromEmail }
}
