package pro.aduki.core.models

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Slot models a discrete bookable time window.
 */
data class Slot(
    val start: String = "",
    val end: String = "",
    val available: Boolean = true
)

/**
 * AppointmentRecord is one calendar event as the server holds it
 * (`/user/events`): an iCalendar object ([ical]) and the fields the server
 * derived from it. Times are ISO-8601 UTC; an all-day event the server
 * could not read has empty [start] and [end] (the SDK reads them from [ical]).
 */
data class AppointmentRecord(
    val hex: String = "",
    val uid: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
    val timezone: String = "UTC",
    /** iCalendar `STATUS`, upper case (`CONFIRMED`, `TENTATIVE`, `CANCELLED`), or empty. */
    val status: String = "",
    val location: String = "",
    val notes: String = "",
    val attendees: List<String> = emptyList(),
    val organizer: String = "",
    val sequence: Int = 0,
    val rrule: String = "",
    val recurring: Boolean = false,
    val etag: String = "",
    val ical: String = "",
    val created: String = "",
    val updated: String = "",
    val calendar: String = ""
)

/**
 * CalendarRecord is one calendar collection (`/user/calendars`).
 */
data class CalendarRecord(
    val hex: String = "",
    val name: String = "",
    val description: String? = null,
    val color: String? = null,
    val timezone: String = "UTC",
    val default: Boolean = false,
    val writable: Boolean = true,
    val href: String = "",
    val created: String = "",
    val updated: String = ""
) {
    /** Alias for [color] matching UK spelling. */
    val colour: String? get() = color
}

/**
 * Place is where a booking happens: `{kind, value}` on the wire, e.g.
 * `Place("video", "https://meet.example/abc")`. The value is shown to
 * guests once they have booked.
 */
data class Place(
    val kind: String = "",
    val value: String = ""
)

/**
 * ServiceRecord is a booking page (`/user/pages`): what guests book, with
 * its lengths, buffers, booking window and form.
 */
data class ServiceRecord(
    val hex: String = "",
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    /** Bookable lengths, minutes; the first is the default. */
    val lengths: List<Int> = listOf(30),
    /** Buffer before a booking, minutes. */
    val before: Int = 0,
    /** Buffer after a booking, minutes. */
    val after: Int = 0,
    /** Minimum notice, minutes. */
    val notice: Int = 60,
    /** How far ahead guests can book, days. */
    val horizon: Int = 60,
    /** Slot grid, minutes. */
    val increment: Int = 30,
    /** `public`, `link` or `internal`. */
    val visibility: String = "public",
    /** `auto` or `manual`. */
    val confirm: String = "auto",
    val place: Place? = null,
    /** The booking form, a JSON array. */
    val questions: String = "[]",
    /** Reminder offsets, minutes before the start; null is the server default (24 h and 1 h). */
    val reminders: List<Int>? = null,
    val active: Boolean = true,
    /** The hex of the schedule whose windows it offers (`schedule` of a page view). */
    val schedule: String = "",
    val created: String = "",
    val updated: String = ""
) {
    /** The default length, minutes. */
    val duration: Int get() = lengths.firstOrNull() ?: 30
}

/**
 * OfferRecord is a service a booking page sells (`/user/schedule/services`):
 * its own length, buffers, price and form. A booker of such a page picks
 * one ([BookRequest.offer]).
 */
data class OfferRecord(
    val hex: String = "",
    /** The page (service) hex it is sold on. */
    val page: String = "",
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    /** Minutes. */
    val length: Int = 30,
    /** Buffers, minutes; null follows the page. */
    val before: Int? = null,
    val after: Int? = null,
    /** Minor units of [currency]; 0 is free. */
    val price: Long = 0,
    val currency: String = "",
    val questions: String = "[]",
    val active: Boolean = true
)

/**
 * BookingRecord is one booking made on a page (`/user/bookings`).
 */
data class BookingRecord(
    val hex: String = "",
    /** The page (service) hex it was booked on. */
    val page: String = "",
    val start: String = "",
    val end: String = "",
    /** The booker's IANA zone. */
    val zone: String = "",
    /** `pending`, `confirmed`, `cancelled`, `done` or `noshow`. */
    val state: String = "",
    val uid: String = "",
    val sequence: Int = 0,
    val notes: String = "",
    val cancelled: String = "",
    /** Why it was cancelled: `guest`, `host`, `unpaid` or `moved`. */
    val reason: String = "",
    val created: String = "",
    /** The guest's manage token (`/book/manage/{token}`), only in the reply to a guest booking. */
    val token: String = "",
    val place: Place? = null,
    /** The guests, only from `GET /user/bookings/{hex}`. */
    val guests: List<Address> = emptyList()
)

/**
 * Stats counts the bookings of one [service] that start in a range, by
 * state, and the views of its public page on the days the range touches.
 * [views] is null only when the reply has none.
 */
data class Stats(
    val service: String = "",
    val views: Int? = null,
    val bookings: Int = 0,
    val confirmed: Int = 0,
    val pending: Int = 0,
    val cancelled: Int = 0,
    val done: Int = 0,
    val noshow: Int = 0
)

/**
 * Response is an attendee's answer to an invitation (iCalendar `PARTSTAT`).
 */
enum class Response(val partstat: String) {
    Accepted("ACCEPTED"),
    Declined("DECLINED"),
    Tentative("TENTATIVE")
}

private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
private val FREQS = setOf("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")

private fun zone(id: String) {
    try {
        ZoneId.of(id)
    } catch (e: DateTimeException) {
        throw IllegalArgumentException("timezone: not an IANA zone: $id", e)
    }
}

/** Checks an RRULE value (RFC 5545 §3.3.10) enough to refuse what the server cannot expand. */
internal fun rule(text: String) {
    if (text.isBlank()) return
    val parts = text.split(';').filter { it.isNotBlank() }.associate {
        require('=' in it) { "rrule: '$it' is not KEY=VALUE" }
        it.substringBefore('=').uppercase() to it.substringAfter('=')
    }
    require(parts["FREQ"]?.uppercase() in FREQS) { "rrule: FREQ is required (DAILY, WEEKLY, MONTHLY, YEARLY, ...)" }
    require(!(parts.containsKey("COUNT") && parts.containsKey("UNTIL"))) { "rrule: COUNT and UNTIL exclude each other" }
    parts["COUNT"]?.let { require((it.toIntOrNull() ?: 0) > 0) { "rrule: COUNT must be positive" } }
    parts["INTERVAL"]?.let { require((it.toIntOrNull() ?: 0) > 0) { "rrule: INTERVAL must be positive" } }
}

private fun reminders(list: List<Int>) {
    require(list.all { it in 0..40_320 }) { "reminders: minutes before the start, 0 to 4 weeks" }
    require(list.toSet().size == list.size) { "reminders: no duplicates" }
}

/**
 * EventRequest describes a new calendar event: a personal event, an
 * all-day event or a repeating one. All-day events take the dates of
 * [start] and [end] in [timezone], [end] exclusive (a one-day event may
 * pass the same day for both).
 */
data class EventRequest(
    val title: String,
    val start: Instant,
    val end: Instant,
    val allday: Boolean = false,
    /** IANA zone the event is held in; recurrences expand in it. */
    val timezone: String = "UTC",
    val location: String = "",
    val notes: String = "",
    /** Attendee e-mail addresses. */
    val attendees: List<String> = emptyList(),
    /** RFC 5545 RRULE value, e.g. `FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10`. */
    val rrule: String = "",
    /** Reminders, minutes before the start. */
    val reminders: List<Int> = emptyList(),
    /** A calendar hex; the default calendar when blank. */
    val calendar: String = ""
) {
    fun validate() {
        require(title.isNotBlank()) { "title is required" }
        require(if (allday) !end.isBefore(start) else end.isAfter(start)) { "end must follow start" }
        zone(timezone)
        require(attendees.all { '@' in it }) { "attendees: e-mail addresses" }
        rule(rrule)
        reminders(reminders)
    }
}

/**
 * EventChanges edits an event: a null field keeps its value, an empty
 * string or list clears it.
 */
data class EventChanges(
    val title: String? = null,
    val start: Instant? = null,
    val end: Instant? = null,
    val allday: Boolean? = null,
    val timezone: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val attendees: List<String>? = null,
    val rrule: String? = null,
    val reminders: List<Int>? = null
) {
    fun validate() {
        title?.let { require(it.isNotBlank()) { "title cannot be cleared" } }
        if (start != null && end != null) require(!end.isBefore(start)) { "end must follow start" }
        timezone?.let(::zone)
        attendees?.let { list -> require(list.all { '@' in it }) { "attendees: e-mail addresses" } }
        rrule?.let(::rule)
        reminders?.let(::reminders)
    }
}

/**
 * Window is a weekly open period of a service: [days] (`mon` ... `sun`)
 * from [begins] to [finishes] (`HH:MM`; at or before [begins] runs into
 * the next day), read in the service's zone.
 */
data class Window(
    val days: List<String>,
    val begins: String,
    val finishes: String
) {
    fun validate() {
        require(days.isNotEmpty() && days.all { it.lowercase() in DAYS }) { "days: mon, tue, wed, thu, fri, sat or sun" }
        for (t in listOf(begins, finishes)) {
            require(Regex("\\d{2}:\\d{2}").matches(t) && runCatching { LocalTime.parse(t) }.isSuccess) { "not a time (HH:MM): $t" }
        }
    }
}

/**
 * Availability describes the weekly open hours of a schedule or service in [zone].
 */
data class Availability(
    val zone: String = "UTC",
    val windows: List<Window> = emptyList()
) {
    fun validate() {
        zone(zone)
        windows.forEach(Window::validate)
    }
}

/**
 * ServiceRequest creates or edits a service (a booking page). On edit a
 * null field keeps its value. [windows] replace the weekly availability,
 * read in [zone].
 */
data class ServiceRequest(
    val name: String? = null,
    /** Lowercase letters, digits and dashes, up to 64. */
    val slug: String? = null,
    val description: String? = null,
    /** Bookable lengths, minutes (5 to 1440); the first is the default. */
    val lengths: List<Int>? = null,
    val before: Int? = null,
    val after: Int? = null,
    val notice: Int? = null,
    val horizon: Int? = null,
    val increment: Int? = null,
    val windows: List<Window>? = null,
    val zone: String? = null,
    /** The booking form, a JSON array. */
    val questions: String? = null,
    val place: Place? = null,
    /** Minutes before the start (up to 5, 5 minutes to 7 days); empty sends none. */
    val reminders: List<Int>? = null,
    val visibility: String? = null,
    val confirm: String? = null,
    val paused: Boolean? = null
) {
    fun validate() {
        name?.let { require(it.isNotBlank()) { "name is required" } }
        slug?.let { s ->
            require(s.isNotEmpty() && s.length <= 64 && !s.startsWith("-") &&
                s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) { "slug: lowercase letters, digits and dashes, up to 64" }
        }
        lengths?.let { l -> require(l.isNotEmpty() && l.all { it in 5..1440 }) { "lengths: 5 minutes to 24 hours" } }
        require(listOfNotNull(before, after, notice).all { it >= 0 }) { "buffers and notice cannot be negative" }
        horizon?.let { require(it in 1..365) { "horizon: 1 to 365 days" } }
        increment?.let { require(it in 5..1440) { "increment: 5 minutes to 24 hours" } }
        windows?.forEach(Window::validate)
        zone?.let(::zone)
        questions?.let { require(it.trimStart().startsWith("[")) { "questions must be a JSON array" } }
        reminders?.let { r ->
            require(r.size <= 5 && r.toSet().size == r.size && r.all { it in 5..10_080 }) {
                "reminders: up to 5 different offsets of 5 minutes to 7 days"
            }
        }
        visibility?.let { require(it in setOf("public", "link", "internal")) { "visibility: public, link or internal" } }
        confirm?.let { require(it in setOf("auto", "manual")) { "confirm: auto or manual" } }
    }
}

/**
 * BookRequest books a slot of a service: as a guest ([name] and [email]
 * required) or as the signed-in account (they are ignored).
 */
data class BookRequest(
    val start: Instant,
    /** Minutes; the service's default when null. */
    val length: Int? = null,
    /** The booker's IANA zone. */
    val zone: String = "UTC",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val notes: String = "",
    val answers: Map<String, String> = emptyMap(),
    /** An offer hex of the page ([OfferRecord]); needed on a page that sells offers. */
    val offer: String = ""
) {
    fun validate(guest: Boolean = true) {
        if (guest) {
            require(name.isNotBlank()) { "name is required" }
            require('@' in email) { "email is required" }
        }
        zone(zone)
        length?.let { require(it in 5..1440) { "length: 5 minutes to 24 hours" } }
    }
}
