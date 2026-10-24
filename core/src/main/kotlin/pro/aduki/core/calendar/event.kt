package pro.aduki.core.calendar

import pro.aduki.core.models.EventChanges
import pro.aduki.core.models.EventRequest
import pro.aduki.core.models.Response
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Attendee is one `ATTENDEE` of an event: the address without `mailto:`
 * and its participation status (`NEEDS-ACTION`, `ACCEPTED`, ...).
 */
data class Attendee(
    val email: String,
    val status: String = "NEEDS-ACTION",
    val name: String = ""
)

/**
 * Event is what the SDK reads from the master VEVENT of an iCalendar
 * object. Times are epoch milliseconds; an all-day event's are UTC
 * midnight of its dates, [end] exclusive.
 */
data class Event(
    val uid: String = "",
    val title: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val allday: Boolean = false,
    /** The zone of `DTSTART` (`TZID`), `UTC` for UTC or floating times. */
    val timezone: String = "UTC",
    val location: String = "",
    val notes: String = "",
    val attendees: List<Attendee> = emptyList(),
    val organizer: String = "",
    /** `STATUS`, upper case, or empty. */
    val status: String = "",
    val sequence: Int = 0,
    val rrule: String = "",
    val exdate: List<Long> = emptyList(),
    /** Minutes before the start, from the VALARMs. */
    val reminders: List<Int> = emptyList(),
    /** `METHOD` of the object (iTIP), or empty. */
    val method: String = "",
    /** `X-ADUKI-SERVICE`: the service slug of a booking, or empty. */
    val service: String = ""
)

/**
 * Events reads, builds and edits event objects (`VCALENDAR` with one
 * master `VEVENT`). Edits change only the properties they are about and
 * keep everything else of the object (alarms, unknown properties,
 * overrides).
 */
object Events {
    private const val PRODID = "-//aduki//kotlin sdk//EN"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val DAY = 86_400_000L

    /** The calendar object in [ical] (the first VCALENDAR), or null. */
    fun calendar(ical: String): Component? = Ics.parse(ical).firstOrNull { it.name == "VCALENDAR" }

    /** The master VEVENT of [calendar]: the first without `RECURRENCE-ID`. */
    fun master(calendar: Component): Component? {
        val events = calendar.components("VEVENT")
        return events.firstOrNull { it.first("RECURRENCE-ID") == null } ?: events.firstOrNull()
    }

    /** The event in [ical], or null when it holds no VEVENT. */
    fun read(ical: String): Event? {
        val cal = calendar(ical) ?: return null
        return master(cal)?.let { read(it, cal.first("METHOD")?.value.orEmpty()) }
    }

    /** The fields of [vevent]. */
    fun read(vevent: Component, method: String = ""): Event {
        val dtstart = vevent.first("DTSTART")
        val allday = dtstart?.let(::dated) ?: false
        val start = dtstart?.let(::time) ?: 0L
        val end = vevent.first("DTEND")?.let(::time)
            ?: vevent.first("DURATION")?.let { span(it.value)?.let { d -> start + d } }
            ?: if (allday) start + DAY else start
        return Event(
            uid = vevent.first("UID")?.value.orEmpty(),
            title = vevent.text("SUMMARY").orEmpty(),
            start = start,
            end = end,
            allday = allday,
            timezone = dtstart?.param("TZID")?.takeIf { zoned(it) } ?: "UTC",
            location = vevent.text("LOCATION").orEmpty(),
            notes = vevent.text("DESCRIPTION").orEmpty(),
            attendees = vevent.all("ATTENDEE").map {
                Attendee(address(it.value), it.param("PARTSTAT")?.uppercase() ?: "NEEDS-ACTION", it.param("CN").orEmpty())
            },
            organizer = vevent.first("ORGANIZER")?.value?.let(::address).orEmpty(),
            status = vevent.first("STATUS")?.value?.uppercase().orEmpty(),
            sequence = vevent.first("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0,
            rrule = vevent.first("RRULE")?.value.orEmpty(),
            exdate = vevent.all("EXDATE").flatMap { p -> p.value.split(',').mapNotNull { time(p.copy(value = it)) } },
            reminders = vevent.components("VALARM").mapNotNull(::reminder),
            method = method.uppercase(),
            service = vevent.text("X-ADUKI-SERVICE").orEmpty()
        )
    }

    /** A new event object for [request]; [uid] defaults to a random one. */
    fun build(request: EventRequest, uid: String = "${UUID.randomUUID()}@aduki"): String {
        val vevent = Component("VEVENT")
        vevent.add(Property("UID", uid))
        vevent.add(Property("DTSTAMP", STAMP.format(Instant.now())))
        vevent.add(Property("SEQUENCE", "0"))
        times(vevent, request.start, request.end, request.allday, request.timezone)
        vevent.set("SUMMARY", request.title)
        vevent.set("LOCATION", request.location)
        vevent.set("DESCRIPTION", request.notes)
        if (request.rrule.isNotBlank()) vevent.add(Property("RRULE", request.rrule.trim()))
        attendees(vevent, request.attendees)
        alarms(vevent, request.reminders)
        return wrap(listOf(vevent))
    }

    /**
     * [ical] with [changes] applied to its master VEVENT, `SEQUENCE` bumped
     * and `DTSTAMP` renewed.
     */
    fun change(ical: String, changes: EventChanges): String = edit(ical) { vevent ->
        val now = read(vevent)
        changes.title?.let { vevent.set("SUMMARY", it) }
        changes.location?.let { vevent.set("LOCATION", it) }
        changes.notes?.let { vevent.set("DESCRIPTION", it) }
        if (changes.start != null || changes.end != null || changes.allday != null || changes.timezone != null) {
            val start = changes.start ?: Instant.ofEpochMilli(now.start)
            // A new start alone keeps the length.
            val end = changes.end ?: Instant.ofEpochMilli(now.end + (start.toEpochMilli() - now.start))
            times(vevent, start, end, changes.allday ?: now.allday, changes.timezone ?: now.timezone)
        }
        changes.rrule?.let { if (it.isBlank()) vevent.remove("RRULE") else vevent.set(Property("RRULE", it.trim())) }
        changes.attendees?.let { attendees(vevent, it) }
        changes.reminders?.let { alarms(vevent, it) }
        bump(vevent)
        true
    }

    /**
     * [ical] with [address]'s `PARTSTAT` set to [response], or null when
     * [address] is not an attendee. The sequence stays: a reply does not
     * change the event (RFC 5546 §2.1.4).
     */
    fun respond(ical: String, address: String, response: Response): String? {
        var found = false
        val out = edit(ical) { vevent ->
            val at = vevent.properties.indexOfFirst {
                it.name == "ATTENDEE" && address(it.value).equals(address.trim(), ignoreCase = true)
            }
            if (at >= 0) {
                vevent.properties[at] = vevent.properties[at].with("PARTSTAT", response.partstat)
                stamp(vevent)
                found = true
            }
            found
        }
        return if (found) out else null
    }

    /** [ical] marked `STATUS:CANCELLED`, with [reason] as a `COMMENT`. */
    fun cancel(ical: String, reason: String = ""): String = edit(ical) { vevent ->
        vevent.set(Property("STATUS", "CANCELLED"))
        if (reason.isNotBlank()) vevent.set("COMMENT", reason)
        bump(vevent)
        true
    }

    /**
     * Splits an iCalendar file into one object per event UID (the master and
     * its overrides), each with the time zones it uses, ready to create one
     * by one. Components other than VEVENT are dropped.
     */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        for (cal in Ics.parse(text).filter { it.name == "VCALENDAR" }) {
            val zones = cal.components("VTIMEZONE")
            val groups = LinkedHashMap<String, MutableList<Component>>()
            for (vevent in cal.components("VEVENT")) {
                val uid = vevent.first("UID")?.value ?: continue
                groups.getOrPut(uid) { mutableListOf() }.add(vevent)
            }
            for (events in groups.values) {
                val used = events.flatMap { e -> e.properties.mapNotNull { it.param("TZID") } }.toSet()
                out.add(wrap(events, zones.filter { it.first("TZID")?.value in used }))
            }
        }
        return out
    }

    /** One VCALENDAR holding the events of every object in [icals], time zones once each. */
    fun join(icals: List<String>): String {
        val events = mutableListOf<Component>()
        val zones = LinkedHashMap<String, Component>()
        for (ical in icals) {
            val cal = calendar(ical) ?: continue
            cal.components("VTIMEZONE").forEach { z -> z.first("TZID")?.value?.let { zones.putIfAbsent(it, z) } }
            events.addAll(cal.components("VEVENT"))
        }
        return wrap(events, zones.values.toList())
    }

    // ── Times ────────────────────────────────────────────────────────

    /** Whether a DATE value (all-day). */
    private fun dated(p: Property): Boolean =
        p.param("VALUE").equals("DATE", ignoreCase = true) || p.value.trim().length == 8

    /** Epoch millis of a DATE or DATE-TIME property: UTC, `TZID`, or floating (read as UTC, as the server does). */
    fun time(p: Property): Long? {
        val v = p.value.trim()
        return try {
            when {
                dated(p) -> LocalDate.parse(v.take(8), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                v.endsWith("Z", ignoreCase = true) -> LocalDateTime.parse(v.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
                else -> {
                    val zone = p.param("TZID")?.takeIf { zoned(it) }?.let { ZoneId.of(it) } ?: ZoneOffset.UTC
                    LocalDateTime.parse(v, LOCAL).atZone(zone).toInstant().toEpochMilli()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun zoned(id: String): Boolean = try {
        ZoneId.of(id)
        true
    } catch (_: DateTimeException) {
        false
    }

    private fun times(vevent: Component, start: Instant, end: Instant, allday: Boolean, timezone: String) {
        vevent.remove("DURATION")
        if (allday) {
            val zone = ZoneId.of(timezone)
            val first = start.atZone(zone).toLocalDate()
            val last = end.atZone(zone).toLocalDate().let { if (it.isAfter(first)) it else first.plusDays(1) }
            vevent.set(Property("DTSTART", DATE.format(first), listOf("VALUE" to "DATE")))
            vevent.set(Property("DTEND", DATE.format(last), listOf("VALUE" to "DATE")))
        } else if (timezone == "UTC" || timezone == "Z" || timezone == "Etc/UTC") {
            vevent.set(Property("DTSTART", STAMP.format(start)))
            vevent.set(Property("DTEND", STAMP.format(end)))
        } else {
            val zone = ZoneId.of(timezone)
            vevent.set(Property("DTSTART", LOCAL.format(start.atZone(zone)), listOf("TZID" to timezone)))
            vevent.set(Property("DTEND", LOCAL.format(end.atZone(zone)), listOf("TZID" to timezone)))
        }
    }

    /** A duration value (`PT15M`, `-P1D`, `P1W`) in millis, or null. */
    private fun span(text: String): Long? {
        val t = text.trim().uppercase()
        val negative = t.startsWith("-")
        val body = t.removePrefix("-").removePrefix("+")
        val millis = try {
            val weeks = Regex("P(\\d+)W").matchEntire(body)
            if (weeks != null) weeks.groupValues[1].toLong() * 7 * DAY else Duration.parse(body).toMillis()
        } catch (_: Exception) {
            return null
        }
        return if (negative) -millis else millis
    }

    // A VALARM relative to the start, as minutes before it.
    private fun reminder(alarm: Component): Int? {
        val trigger = alarm.first("TRIGGER") ?: return null
        if (trigger.param("VALUE").equals("DATE-TIME", ignoreCase = true)) return null
        if (trigger.param("RELATED").equals("END", ignoreCase = true)) return null
        val millis = span(trigger.value) ?: return null
        return (-millis / 60_000L).toInt().takeIf { it >= 0 }
    }

    private fun alarms(vevent: Component, minutes: List<Int>) {
        vevent.children.removeAll { it.name == "VALARM" }
        for (m in minutes) {
            vevent.children.add(
                Component(
                    "VALARM",
                    mutableListOf(
                        Property("ACTION", "DISPLAY"),
                        Property("DESCRIPTION", "Reminder"),
                        Property("TRIGGER", if (m == 0) "PT0M" else "-PT${m}M")
                    )
                )
            )
        }
    }

    // Keeps the line (and PARTSTAT) of an attendee still invited.
    private fun attendees(vevent: Component, emails: List<String>) {
        val kept = vevent.all("ATTENDEE").associateBy { address(it.value) }
        val at = vevent.properties.indexOfFirst { it.name == "ATTENDEE" }.let { if (it < 0) vevent.properties.size else it }
        vevent.remove("ATTENDEE")
        val lines = emails.map { it.trim().lowercase() }.distinct().map { email ->
            kept[email] ?: Property("ATTENDEE", "mailto:$email", listOf("PARTSTAT" to "NEEDS-ACTION", "RSVP" to "TRUE"))
        }
        vevent.properties.addAll(minOf(at, vevent.properties.size), lines)
    }

    private fun address(raw: String): String = raw.trim().let {
        if (it.length >= 7 && it.substring(0, 7).equals("mailto:", ignoreCase = true)) it.substring(7) else it
    }.lowercase()

    private fun stamp(vevent: Component) {
        vevent.set(Property("DTSTAMP", STAMP.format(Instant.now())))
    }

    private fun bump(vevent: Component) {
        val sequence = vevent.first("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0
        vevent.set(Property("SEQUENCE", (sequence + 1).toString()))
        stamp(vevent)
    }

    // Applies [block] to the master VEVENT and renders the object again.
    private fun edit(ical: String, block: (Component) -> Boolean): String {
        val cal = calendar(ical) ?: throw IllegalArgumentException("not an iCalendar object")
        val vevent = master(cal) ?: throw IllegalArgumentException("no VEVENT in the object")
        return if (block(vevent)) Ics.render(cal) else ical
    }

    private fun wrap(events: List<Component>, zones: List<Component> = emptyList()): String {
        val cal = Component(
            "VCALENDAR",
            mutableListOf(Property("VERSION", "2.0"), Property("PRODID", PRODID))
        )
        cal.children.addAll(zones)
        cal.children.addAll(events)
        return Ics.render(cal)
    }
}
