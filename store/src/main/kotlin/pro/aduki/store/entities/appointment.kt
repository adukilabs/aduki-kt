package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Appointment represents a scheduled meeting or calendar booking.
 */
@Entity
data class Appointment(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var tenant: String = "",
    @Index var service: String = "",
    @Index var host: String = "",
    @Index var start: Long = 0L,
    var end: Long = 0L,
    var timezone: String = "UTC",
    @Index var status: String = "confirmed",
    var uid: String = "",
    var sequence: Int = 0,
    var method: String = "REQUEST",
    @Convert(converter = SealedText::class, dbType = String::class) var location: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var notes: String = "",
    var cancelled: Long = 0L,
    var rescheduled: String = "",
    var updated: Long = 0L
)

