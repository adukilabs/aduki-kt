package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Mailbox represents an email folder with CONDSTORE sequence tracking.
 */
@Entity
data class Mailbox(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    var name: String = "",
    var role: String = "", // inbox, sent, trash, archive, drafts
    var uidnext: Long = 0,
    var uidvalidity: Long = 0,
    var modseq: Long = 0,
    var exists: Int = 0,
    var unseen: Int = 0
)
