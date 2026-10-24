package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Appointment is one calendar event: a personal event, an invitation or a
 * booking. [ical] is the event's iCalendar object (the source of truth
 * sent to the server); the other columns are read from it for queries.
 * Times are epoch millis; an all-day event's are UTC midnight of its
 * dates, [end] exclusive. A [hex] starting with `local-` is an event not
 * yet created on the server.
 */
@Entity
data class Appointment(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var tenant: String = "",
    @Index var uid: String = "",
    /** The calendar hex it was created in; empty for the default calendar. */
    var calendar: String = "",
    /** The service slug of a booking (`X-ADUKI-SERVICE`), or empty. */
    @Index var service: String = "",
    @Index var start: Long = 0L,
    var end: Long = 0L,
    var allday: Boolean = false,
    var timezone: String = "UTC",
    /** `confirmed`, `tentative` or `cancelled`. */
    @Index var status: String = "confirmed",
    var sequence: Int = 0,
    /** iTIP `METHOD` of the object, if it came with one. */
    var method: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var title: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var location: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var notes: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var organizer: String = "",
    /** One `email=PARTSTAT` per line. */
    @Convert(converter = SealedText::class, dbType = String::class) var attendees: String = "",
    /** RRULE value, or empty for a single event. */
    var rrule: String = "",
    /** Excluded occurrence starts, epoch millis, comma separated. */
    var exdate: String = "",
    /** Reminders, minutes before the start, comma separated. */
    var reminders: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var ical: String = "",
    var etag: String = "",
    var cancelled: Long = 0L,
    /** The start (epoch millis) before the last reschedule, 0 if never moved. */
    var moved: Long = 0L,
    var updated: Long = 0L
) {
    /** [attendees] as `(email, PARTSTAT)` pairs. */
    fun guests(): List<Pair<String, String>> = attendees.lines().filter { it.isNotBlank() }.map {
        it.substringBefore('=') to it.substringAfter('=', "NEEDS-ACTION")
    }

    fun alarms(): List<Int> = reminders.split(',').mapNotNull { it.trim().toIntOrNull() }

    fun excluded(): List<Long> = exdate.split(',').mapNotNull { it.trim().toLongOrNull() }

    /** Not yet on the server. */
    fun local(): Boolean = hex.startsWith(LOCAL)

    companion object {
        const val LOCAL = "local-"
    }
}
