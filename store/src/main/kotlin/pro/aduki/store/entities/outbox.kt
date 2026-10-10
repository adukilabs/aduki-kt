package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Outbox stores atomic pending mutations to be dispatched to Aduki.
 */
@Entity
data class Outbox(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var action: String = "", // "send", "flag", "move", "delete"
    var payload: ByteArray = byteArrayOf(),
    @Index var created: Long = System.currentTimeMillis(),
    var attempts: Int = 0,
    var nextRetry: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Outbox
        return id == other.id && action == other.action && created == other.created
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + created.hashCode()
        return result
    }
}
