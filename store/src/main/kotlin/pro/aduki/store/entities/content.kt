package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Content caches the body of one message (by [hex]) once fetched, so it reads
 * offline. Kept apart from [Message] so lists never load bodies. A row holds
 * the whole body (`BodyMode.Both`); a narrower reading is served from it.
 */
@Entity
data class Content(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var text: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var html: String = "",
    /** The attachments as a JSON array of `{blob, name, type, size, cid, inline}`. */
    @Convert(converter = SealedText::class, dbType = String::class) var attachments: String = "",
    var fetched: Long = 0,
    /** Where the quoted reply starts in [text], or null. */
    var quoteStart: Int? = null,
    /** The message has a real text part. Read only when [sanitised]: older rows derive it from [text]. */
    var hasText: Boolean = false,
    /** The message has a real HTML part. Read only when [sanitised]: older rows derive it from [html]. */
    var hasHtml: Boolean = false,
    /** The server sanitised [html] and unwrapped [text] (both readings are kept). False in rows cached before 0.6.2. */
    var sanitised: Boolean = false,
    /** Saved on this device (a draft), not fetched: never refetched for reading support. */
    var local: Boolean = false
)
