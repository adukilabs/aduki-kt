# Scheduling Service Reference

The `Scheduling` service (`client.scheduling`) covers calendar events
("appointments"), services (booking pages), their availability, slots and
bookings.

- **Events** are iCalendar objects. They live in the local store: every write
  is applied there at once, queued in the outbox and sent to `/user/events`.
  Writes need a local store (`Aduki.Builder.store`) and throw
  `IllegalStateException("no local store")` without one.
- **Services** are the account's booking pages (`/user/pages`). They are read
  and written online.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Scheduling internal constructor(...) {
    // Events, from the local store
    val appointments: StateFlow<List<Appointment>>?
    val upcoming: Flow<List<Appointment>>?
    fun observe(start: Instant, end: Instant): StateFlow<List<Occurrence>>?
    fun expand(start: Instant, end: Instant): List<Occurrence>
    fun get(hex: String): Appointment?
    suspend fun sync(): Int

    // Event writes: optimistic, queued in the outbox
    suspend fun create(request: EventRequest, notify: Boolean = false): Appointment
    suspend fun update(hex: String, changes: EventChanges, notify: Boolean = false): Appointment
    suspend fun reschedule(hex: String, start: Instant, end: Instant, notify: Boolean = false): Appointment
    suspend fun respond(hex: String, response: Response, address: String? = null, comment: String = ""): Appointment
    suspend fun cancel(hex: String, reason: String = "", notify: Boolean = false): Appointment
    suspend fun remove(hex: String, notify: Boolean = false)
    suspend fun importIcs(text: String, calendar: String = ""): List<Appointment>
    fun exportIcs(hexes: List<String>? = null): String

    // Services (booking pages), online
    suspend fun services(): List<ServiceRecord>
    suspend fun service(hex: String): ServiceRecord
    suspend fun createService(request: ServiceRequest): ServiceRecord
    suspend fun updateService(hex: String, request: ServiceRequest): ServiceRecord
    suspend fun removeService(hex: String)
    suspend fun offers(service: String = ""): List<OfferRecord>
    fun bookingUrl(service: ServiceRecord, base: String = client.options.endpoint): String

    // Slots and bookings, online
    suspend fun slots(service: String, start: Instant, end: Instant, length: Int? = null): List<Slot>
    suspend fun book(service: String, request: BookRequest): BookingRecord     // as a guest
    suspend fun reserve(service: String, request: BookRequest): BookingRecord  // as the account
    suspend fun bookings(upcoming: Boolean = false): List<BookingRecord>
    suspend fun booking(hex: String): BookingRecord
    suspend fun updateBooking(hex: String, state: String): BookingRecord
    suspend fun stats(service: String, start: Instant, end: Instant): Stats
}
```

Request classes (`pro.aduki.core.models`) check themselves with `validate()`:
`EventRequest` (title, start, end, all-day, zone, location, notes, attendees,
RRULE, reminders in minutes, calendar), `EventChanges` (the same fields, null
keeps), `ServiceRequest` (name, slug, description, lengths, before and after
buffers, notice, horizon, increment, weekly `Window`s and their zone,
questions as a JSON array, `Place`, reminders, visibility, confirm, paused) and
`BookRequest`.

---

## 2. Events

### Create a personal, all-day or repeating event

```kotlin
val standup = client.scheduling.create(
    EventRequest(
        title = "Standup",
        start = Instant.parse("2026-11-02T06:00:00Z"),
        end = Instant.parse("2026-11-02T06:30:00Z"),
        timezone = "Africa/Nairobi",
        attendees = listOf("bob@example.com"),
        rrule = "FREQ=WEEKLY;BYDAY=MO,WE",
        reminders = listOf(10)
    )
)
```

The event is stored at once with a `local-...` hex and becomes the server's
hex once the outbox sent it. An all-day event (`allday = true`) takes the dates
of `start` and `end` in `timezone`, `end` exclusive.

### Edit, reschedule, answer, cancel, delete

```kotlin
client.scheduling.update(hex, EventChanges(location = "Room 4", reminders = emptyList()))
client.scheduling.reschedule(hex, newStart, newEnd)
client.scheduling.respond(hex, Response.Accepted, comment = "See you")  // the account's own address
client.scheduling.cancel(hex, reason = "Off sick")  // STATUS:CANCELLED, kept
client.scheduling.remove(hex, notify = true)        // attendees get a CANCEL
```

Each edit changes only what it is about in the stored iCalendar object (alarms,
unknown properties and overrides are kept), bumps `SEQUENCE` (not for an
answer) and sends the whole object, so retries converge.

`respond` sends `POST /user/events/{hex}/reply`: the server sets the
account's `PARTSTAT` and sends the organizer an iTIP REPLY with the
`comment`. For an event the server will not answer for (no organizer, or the
account is not an attendee; `422`), the answered object is stored with
`PATCH` instead and nobody is told.

### Telling the attendees

Attendees hear nothing unless asked. Pass `notify = true` and, for an event
the account organizes, the server sends each attendee an iTIP message by
mail:

- `create(request, notify = true)`, `update(..., notify = true)` and
  `reschedule(..., notify = true)` send a REQUEST (`"notify": true` in the
  body);
- `cancel(hex, notify = true)` sends the cancelled event as a REQUEST
  carrying `STATUS:CANCELLED`;
- `remove(hex, notify = true)` sends a CANCEL (`DELETE ...?notify=true`).

The flag is kept in the outbox entry, so a write made offline notifies when it
is sent. Nothing is sent for an event the account does not organize, or one
without attendees.

### Occurrences in a range

```kotlin
val week: StateFlow<List<Occurrence>>? = client.scheduling.observe(monday, monday.plus(Duration.ofDays(7)))
```

Repeating events are expanded on the device (RRULE less EXDATE, in the
event's zone); cancelled events are left out. `Occurrence` holds the
`appointment` and the occurrence's `start` and `end`.

### iCalendar files

```kotlin
val added = client.scheduling.importIcs(text)   // one event per UID; known UIDs are replaced
val file = client.scheduling.exportIcs()        // every stored event
```

---

## 3. Services

```kotlin
val consult = client.scheduling.createService(
    ServiceRequest(
        name = "Consultation",
        slug = "consult",
        lengths = listOf(30, 60),
        after = 10,
        windows = listOf(Window(listOf("mon", "tue", "wed"), "09:00", "17:00")),
        zone = "Africa/Nairobi",
        place = Place("video", "https://meet.example/consult"),
        questions = """[{"label":"Company"}]"""
    )
)
client.scheduling.updateService(consult.hex, ServiceRequest(paused = true))
val url = client.scheduling.bookingUrl(consult)
```

With `windows` a service gets its own schedule, named after its slug; without,
it uses the account's main schedule. A service that has bookings cannot be
deleted: pause it.

### Slots and bookings

```kotlin
val slots = client.scheduling.slots(consult.hex, from, to, length = 30)
val booking = client.scheduling.book(
    consult.hex,
    BookRequest(start = Instant.parse(slots.first().start), name = "Jane Doe", email = "jane@example.com")
)
client.scheduling.updateBooking(booking.hex, "cancelled")
val month = client.scheduling.stats(consult.hex, from, to)
```

A taken slot answers `409` (`slot.taken`): read the slots again.

`stats` reads `GET /user/pages/{hex}/stats?from&to` (at most a year apart):
the page's bookings that start in the range by state (`bookings`,
`confirmed`, `pending`, `cancelled`, `done`, `noshow`) and `views`, the views
of its public page on the days the range touches. Bookings carry the `page`
they were made on, and pages their `schedule` (a hex), which `updateService`
uses to change a service's windows.

---

## 4. Routes

| Call | Route |
|---|---|
| `sync` | `GET /user/events?page&limit` |
| outbox `event_create` | `POST /user/events` `{ical, calendar?, notify?}` |
| outbox `event_update` | `PATCH /user/events/{hex}` `{ical, notify?}` |
| outbox `event_reply` | `POST /user/events/{hex}/reply` `{partstat, comment?}`; on `422`, `PATCH /user/events/{hex}` |
| outbox `event_delete` | `DELETE /user/events/{hex}?notify=` |
| `services`, `service` | `GET /user/pages`, `GET /user/pages/{hex}` |
| `createService`, `updateService`, `removeService` | `POST /user/pages`, `PATCH /user/pages/{hex}`, `DELETE /user/pages/{hex}`; windows: `POST /user/schedules`, `PUT /user/schedules/{hex}/rules` |
| `offers` | `GET /user/schedule/services?page=` |
| `slots` | `GET /user/book/pages/{hex}/slots?from&to&length` |
| `book` | `POST /book/pages/{hex}/bookings` (public) |
| `reserve` | `POST /user/book/pages/{hex}/bookings` |
| `bookings`, `booking`, `updateBooking` | `GET /user/bookings?upcoming`, `GET /user/bookings/{hex}`, `PATCH /user/bookings/{hex}` `{state}` |
| `stats` | `GET /user/pages/{hex}/stats?from&to` |

---

## 5. Notes

- The SDK syncs the whole calendar (`GET /user/events`) and expands repeats on
  the device. `/user/events/range` also matches repeating events by later
  occurrences, and all-day events carry their start and end.
- A `PATCH` with field edits (`summary`, `description`, `location`) keeps
  attendees and alarms; the SDK sends the whole object anyway.
