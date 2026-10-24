package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Mailbox represents an email folder with CONDSTORE sequence tracking.
 */
@Entity
data class Mailbox(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var name: String = "",
    var role: String = "", // inbox, sent, trash, archive, drafts
    var uidnext: Long = 0,
    var uidvalidity: Long = 0,
    var modseq: Long = 0,
    var exists: Int = 0,
    var unseen: Int = 0
) {
    companion object {
        /** Hex prefix of a mailbox created offline, until the server names it. */
        const val PENDING = "local-"
    }

    /** Created offline and not on the server yet. */
    fun pending(): Boolean = hex.startsWith(PENDING)
}
