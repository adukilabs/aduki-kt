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
    suspend fun create(request: EventRequest): Appointment
    suspend fun update(hex: String, changes: EventChanges): Appointment
    suspend fun reschedule(hex: String, start: Instant, end: Instant): Appointment
    suspend fun respond(hex: String, response: Response, address: String? = null): Appointment
    suspend fun cancel(hex: String, reason: String = ""): Appointment
    suspend fun remove(hex: String)
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
client.scheduling.respond(hex, Response.Accepted)   // the account's own address
client.scheduling.cancel(hex, reason = "Off sick")  // STATUS:CANCELLED, kept
client.scheduling.remove(hex)
```

Each edit changes only what it is about in the stored iCalendar object (alarms,
unknown properties and overrides are kept), bumps `SEQUENCE` (not for an
answer) and sends the whole object, so retries converge.

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

---

## 4. Routes

| Call | Route |
|---|---|
| `sync` | `GET /user/events?page&limit` |
| outbox `event_create` | `POST /user/events` `{ical, calendar?}` |
| outbox `event_update` | `PATCH /user/events/{hex}` `{ical}` |
| outbox `event_reply` | `POST /user/events/{hex}/reply` `{partstat}` (proposed), else `PATCH /user/events/{hex}` |
| outbox `event_delete` | `DELETE /user/events/{hex}` |
| `services`, `service` | `GET /user/pages`, `GET /user/pages/{hex}` |
| `createService`, `updateService`, `removeService` | `POST /user/pages`, `PATCH /user/pages/{hex}`, `DELETE /user/pages/{hex}`; windows: `POST /user/schedules`, `PUT /user/schedules/{hex}/rules` |
| `offers` | `GET /user/schedule/services?page=` |
| `slots` | `GET /user/book/pages/{hex}/slots?from&to&length` |
| `book` | `POST /book/pages/{hex}/bookings` (public) |
| `reserve` | `POST /user/book/pages/{hex}/bookings` |
| `bookings`, `booking`, `updateBooking` | `GET /user/bookings?upcoming`, `GET /user/bookings/{hex}`, `PATCH /user/bookings/{hex}` `{state}` |
| `stats` | `GET /user/pages/{hex}/stats?from&to` (proposed), else counted on the device |

---

## 5. Limits of the server today

- **Answers are not sent to the organizer.** Until `POST /user/events/{hex}/reply`
  exists, `respond` stores the answer in the account's copy only.
- **All-day events have no server times.** The server does not read
  `DTSTART;VALUE=DATE`, so `/user/events/range` misses them; the SDK reads the
  dates from the object.
- **Range queries match the first occurrence only.** The SDK therefore syncs the
  whole calendar and expands repeats on the device.
- **Attendees are not notified** of events created or changed over REST.
- **Statistics.** Until `GET /user/pages/{hex}/stats` exists, `stats` counts
  bookings on the device for the whole account (`Stats.service` is empty,
  `views` is null).
- **A page does not show its schedule**, so `updateService` finds a service's
  schedule by its slug.
