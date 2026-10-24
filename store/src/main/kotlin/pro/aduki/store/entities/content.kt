package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Content caches the body of one message (by [hex]) once fetched, so it reads
 * offline. Kept apart from [Message] so lists never load bodies.
 */
@Entity
data class Content(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var text: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var html: String = "",
    /** The attachments as a JSON array of `{blob, name, type, size, cid, inline}`. */
    @Convert(converter = SealedText::class, dbType = String::class) var attachments: String = "",
    var fetched: Long = 0
)
