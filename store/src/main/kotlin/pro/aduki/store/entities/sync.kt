package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Sync stores token state for delta synchronizers.
 */
@Entity
data class Sync(
    @Id var id: Long = 0,
    @Index var target: String = "", // "contacts", "mailbox_hex"
    var token: String = "",
    var timestamp: Long = 0
)
