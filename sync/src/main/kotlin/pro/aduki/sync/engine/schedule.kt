package pro.aduki.sync.engine

import io.objectbox.Box
import io.objectbox.BoxStore
import org.json.JSONObject
import pro.aduki.core.calendar.Event
import pro.aduki.core.calendar.Events
import pro.aduki.core.models.AppointmentRecord
import pro.aduki.core.models.EventChanges
import pro.aduki.core.models.EventRequest
import pro.aduki.core.models.Response
import pro.aduki.net.http.Scheduling
import pro.aduki.store.entities.Appointment
import pro.aduki.store.entities.Appointment_
import pro.aduki.sync.outbox.Manager
import java.time.Instant
import java.util.UUID

/**
 * Schedule keeps the local calendar: it reconciles events with the server
 * and applies every write optimistically to the store, journaling it in
 * the outbox (through [manager], so its count stays right) for
 * `ScheduleDispatcher` to send.
 *
 * Outbox actions, payload `{hex, uid, ical?, calendar?, partstat?, comment?, notify?}`:
 * `event_create`, `event_update` (the whole object after the edit, so
 * retries and reordering converge), `event_reply` (an RSVP: the object and
 * the answer), `event_delete`. `notify: true` asks the server to send the
 * attendees an iTIP message for an event the account organizes.
 */
class Schedule(
    private val store: BoxStore,
    private val net: Scheduling,
    private val manager: Manager
) {
    private val box: Box<Appointment> = store.boxFor(Appointment::class.java)

    /**
     * Loads every event (`GET /user/events`) into the store: new and changed
     * events are written, events gone from the server are dropped. Events
     * with a queued write keep their local state until it is sent.
     */
    fun sync(): Int {
        val records = net.events()
        val waiting = pending()
        return store.callInTx {
            val local = box.all
            val byHex = local.associateBy { it.hex }
            val byUid = local.filter { it.local() }.associateBy { it.uid }
            val seen = HashSet<String>()
            val save = mutableListOf<Appointment>()
            for (rec in records) {
                seen.add(rec.hex)
                if (rec.hex in waiting || rec.uid in waiting) continue
                // A create whose reply was lost: adopt the server's copy.
                val row = byHex[rec.hex] ?: byUid[rec.uid] ?: Appointment()
                if (row.id != 0L && row.etag == rec.etag && row.hex == rec.hex) continue
                save.add(fill(row, rec))
            }
            box.put(save)
            val gone = local.filter { !it.local() && it.hex !in seen && it.hex !in waiting && it.uid !in waiting }
            box.remove(gone)
            save.size
        }
    }

    /** The local event with [hex], or null. */
    fun get(hex: String): Appointment? =
        box.query(Appointment_.hex.equal(hex)).build().use { it.findFirst() }

    /** Every stored event ordered chronologically. */
    fun appointments(): List<Appointment> = box.all.sortedBy { it.start }

    /** Creates an event locally (hex `local-...`) and queues it; [notify] invites the attendees. */
    fun create(request: EventRequest, notify: Boolean = false): Appointment {
        request.validate()
        return insert(Events.build(request), request.calendar, notify)
    }

    /** Applies [changes] to an event and queues the new object; [notify] sends it to the attendees. */
    fun update(hex: String, changes: EventChanges, notify: Boolean = false): Appointment {
        changes.validate()
        val row = find(hex)
        val ical = Events.change(source(row), changes)
        val moved = if (changes.start != null && changes.start!!.toEpochMilli() != row.start) row.start else row.moved
        return write(row, ical, UPDATE, Extra(notify = notify)) { it.moved = moved }
    }

    /** Moves an event to [start] .. [end]. */
    fun reschedule(hex: String, start: Instant, end: Instant, notify: Boolean = false): Appointment {
        require(end.isAfter(start)) { "end must follow start" }
        return update(hex, EventChanges(start = start, end = end), notify)
    }

    /** Edits a single occurrence of a recurring event at [occurrenceStart] using a RECURRENCE-ID override. */
    fun updateOccurrence(hex: String, occurrenceStart: Instant, changes: EventChanges, notify: Boolean = false): Appointment {
        changes.validate()
        val row = find(hex)
        val ical = Events.updateOccurrence(source(row), occurrenceStart, changes)
        return write(row, ical, UPDATE, Extra(notify = notify)) { }
    }

    /** Cancels a single occurrence of a recurring event at [occurrenceStart] by appending an EXDATE. */
    fun cancelOccurrence(hex: String, occurrenceStart: Instant, notify: Boolean = false): Appointment {
        val row = find(hex)
        val ical = Events.cancelOccurrence(source(row), occurrenceStart)
        val exdates = row.excluded().toMutableSet()
        exdates.add(occurrenceStart.toEpochMilli())
        return write(row, ical, UPDATE, Extra(notify = notify)) {
            it.exdate = exdates.joinToString(",")
        }
    }

    /** Splits a recurring series at [occurrenceStart]: caps original with UNTIL and creates a new series. */
    fun updateFollowing(hex: String, occurrenceStart: Instant, changes: EventChanges, notify: Boolean = false): Appointment {
        changes.validate()
        val row = find(hex)
        val (originalIcal, newIcal) = Events.updateFollowing(source(row), occurrenceStart, changes)
        write(row, originalIcal, UPDATE, Extra(notify = notify)) { }
        return insert(newIcal, row.calendar, notify)
    }

    /**
     * Answers an invitation as [address]. Throws [IllegalArgumentException]
     * when [address] is not an attendee.
     */
    fun respond(hex: String, response: Response, address: String, comment: String = ""): Appointment {
        val row = find(hex)
        val ical = Events.respond(source(row), address, response)
            ?: throw IllegalArgumentException("$address is not an attendee of $hex")
        return write(row, ical, REPLY, Extra(partstat = response.partstat, comment = comment)) { }
    }

    /**
     * Marks an event cancelled (`STATUS:CANCELLED`); it stays in the
     * calendar. [notify] sends the cancelled object to the attendees.
     */
    fun cancel(hex: String, reason: String = "", notify: Boolean = false): Appointment {
        val row = find(hex)
        return write(row, Events.cancel(source(row), reason), UPDATE, Extra(notify = notify)) {
            it.cancelled = System.currentTimeMillis()
        }
    }

    /** Deletes an event locally and queues the delete; [notify] sends the attendees an iTIP CANCEL. */
    fun remove(hex: String, notify: Boolean = false) {
        val row = find(hex)
        store.runInTx {
            box.remove(row)
            val create = manager.pending().firstOrNull { it.action == CREATE && payload(it.payload).optString("uid") == row.uid }
            if (create != null) {
                // Never reached the server: forget both.
                manager.complete(create.id)
            } else {
                manager.enqueue(DELETE, body(row.hex, row.uid, extra = Extra(notify = notify)), row.hex)
            }
        }
    }

    /**
     * Imports an iCalendar file: one event per UID. An event whose UID is
     * already in the store is replaced, any other is created in [calendar].
     */
    fun import(text: String, calendar: String = ""): List<Appointment> {
        val objects = Events.split(text)
        require(objects.isNotEmpty()) { "no VEVENT in the file" }
        return objects.map { ical ->
            val uid = Events.read(ical)?.uid.orEmpty()
            val known = if (uid.isBlank()) null else box.query(Appointment_.uid.equal(uid)).build().use { it.findFirst() }
            if (known != null) write(known, ical, UPDATE) { } else insert(ical, calendar)
        }
    }

    /** The events with [hexes] (every event when null) as one iCalendar file. */
    fun export(hexes: List<String>? = null): String {
        val rows = if (hexes == null) box.all.sortedBy { it.start } else hexes.map(::find)
        return Events.join(rows.map(::source))
    }

    /** The current hex of the event with [uid] (after its create was sent), or null. */
    fun resolve(uid: String): String? =
        box.query(Appointment_.uid.equal(uid)).build().use { it.findFirst() }?.hex

    /** Gives the local event [old] the server's copy [record] after its create. */
    fun created(old: String, record: AppointmentRecord) {
        store.runInTx {
            val row = get(old) ?: get(record.hex) ?: return@runInTx
            row.hex = record.hex
            row.etag = record.etag
            box.put(row)
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    // What an action carries besides the object.
    private data class Extra(val partstat: String = "", val comment: String = "", val notify: Boolean = false)

    private fun insert(ical: String, calendar: String, notify: Boolean = false): Appointment {
        val event = Events.read(ical) ?: throw IllegalArgumentException("no VEVENT in the object")
        require(event.uid.isNotBlank()) { "the event has no UID" }
        val row = Appointment(hex = Appointment.LOCAL + UUID.randomUUID(), calendar = calendar)
        apply(row, event, ical)
        store.runInTx {
            box.put(row)
            manager.enqueue(CREATE, body(row.hex, row.uid, ical, calendar, Extra(notify = notify)), row.hex)
        }
        return row
    }

    private fun write(row: Appointment, ical: String, action: String, extra: Extra = Extra(), edit: (Appointment) -> Unit): Appointment {
        val event = Events.read(ical) ?: throw IllegalArgumentException("no VEVENT in the object")
        apply(row, event, ical)
        edit(row)
        store.runInTx {
            box.put(row)
            manager.enqueue(action, body(row.hex, row.uid, ical, extra = extra), row.hex)
        }
        return row
    }

    private fun find(hex: String): Appointment = get(hex) ?: throw IllegalArgumentException("Event not found: $hex")

    // The row's object; rows from before 0.5 have none and get one built from their columns.
    private fun source(row: Appointment): String = row.ical.ifBlank {
        Events.build(
            EventRequest(
                title = row.title.ifBlank { "Event" },
                start = Instant.ofEpochMilli(row.start),
                end = Instant.ofEpochMilli(maxOf(row.end, row.start + 1)),
                allday = row.allday,
                timezone = row.timezone.ifBlank { "UTC" },
                location = row.location,
                notes = row.notes
            ),
            row.uid.ifBlank { "${UUID.randomUUID()}@aduki" }
        )
    }

    private fun fill(row: Appointment, rec: AppointmentRecord): Appointment {
        row.hex = rec.hex
        row.etag = rec.etag
        val event = Events.read(rec.ical)
        if (event != null) {
            apply(row, event, rec.ical)
        } else {
            // No object in the reply: keep what the server derived.
            row.uid = rec.uid
            row.title = rec.title
            row.start = instant(rec.start)
            row.end = instant(rec.end)
            row.timezone = rec.timezone
            row.status = status(rec.status)
            row.location = rec.location
            row.notes = rec.notes
            row.organizer = rec.organizer
            row.attendees = rec.attendees.joinToString("\n") { "$it=NEEDS-ACTION" }
            row.sequence = rec.sequence
            row.rrule = rec.rrule
            row.updated = System.currentTimeMillis()
        }
        return row
    }

    private fun apply(row: Appointment, event: Event, ical: String) {
        row.uid = event.uid
        row.title = event.title
        row.start = event.start
        row.end = event.end
        row.allday = event.allday
        row.timezone = event.timezone
        row.status = status(event.status)
        row.sequence = event.sequence
        row.method = event.method
        row.location = event.location
        row.notes = event.notes
        row.organizer = event.organizer
        row.attendees = event.attendees.joinToString("\n") { "${it.email}=${it.status}" }
        row.rrule = event.rrule
        row.exdate = event.exdate.joinToString(",")
        row.reminders = event.reminders.joinToString(",")
        row.service = event.service
        row.ical = ical
        row.updated = System.currentTimeMillis()
    }

    private fun status(text: String): String = when (text.uppercase()) {
        "CANCELLED" -> "cancelled"
        "TENTATIVE" -> "tentative"
        else -> "confirmed"
    }

    private fun instant(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)

    // Hexes and UIDs with a queued write.
    private fun pending(): Set<String> = manager.pending()
        .filter { it.action in ACTIONS }
        .flatMap { val p = payload(it.payload); listOf(p.optString("hex"), p.optString("uid")) }
        .filter { it.isNotBlank() }
        .toSet()

    private fun payload(bytes: ByteArray): JSONObject = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject())

    private fun body(hex: String, uid: String, ical: String? = null, calendar: String = "", extra: Extra = Extra()): ByteArray =
        JSONObject().put("hex", hex).put("uid", uid)
            .apply {
                if (ical != null) put("ical", ical)
                if (calendar.isNotBlank()) put("calendar", calendar)
                if (extra.partstat.isNotBlank()) put("partstat", extra.partstat)
                if (extra.comment.isNotBlank()) put("comment", extra.comment)
                if (extra.notify) put("notify", true)
            }
            .toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val CREATE = "event_create"
        const val UPDATE = "event_update"
        const val DELETE = "event_delete"
        const val REPLY = "event_reply"

        /** Cancel journaled by 0.4 (`{hex, reason}`); sent as an update. */
        const val LEGACY = "appointment_cancel"

        val ACTIONS = setOf(CREATE, UPDATE, REPLY, DELETE, LEGACY)
    }
}
