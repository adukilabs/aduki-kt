# Aduki Android Kotlin SDK Scheduling Engine

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win. gRPC was dropped (D-HOST-5, 2026-10-10): every mention of it below is history.

This guide details the scheduling and appointment subsystem integrated into the Aduki Android Kotlin SDK.

---

## 1. Subsystem Modules

The scheduling capability is distributed across the SDK modules following clean separation of concerns:

- **`:core`**: iCalendar reading and writing (`calendar.Ics`, `calendar.Events`) and recurrence expansion (`calendar.Recurrence`).
- **`:store`**: ObjectBox FlatBuffers models (`Appointment`, which keeps each event's iCalendar object, `Service`) and memory-efficient `Slot` models.
- **`:net`**: Pure OkHttp JSON client for `/user/events` (events as iCalendar objects), `/user/pages` (services), `/user/schedules` (availability), `/user/book/pages/{hex}/slots`, `/user/bookings`, `/user/schedule/services` and the public `/book/pages/{hex}/bookings`. The `/user/appointments`, `/user/services`, `/user/availability` and `/book/{slug}` routes of 0.4 do not exist on the server.
- **`:sync`**: `engine.Schedule` reconciles the calendar and applies event writes optimistically, journaling them in the outbox through `Manager`; `http.ScheduleDispatcher` sends them, as a fallback of `HttpDispatcher`.
- **`:state`**: Reactive `Appointment` repository exposing hot `StateFlow<List<Appointment>>` feeds and `observe(start, end)` occurrences, repeats expanded.
- **`:sdk`**: High-level `Scheduling` service facade attached to `Aduki`.

---

## 2. Quickstart Usage

### Access Active Appointments
```kotlin
// Live observable flow of confirmed appointments
lifecycleScope.launch {
    client.scheduling.appointments.collect { list ->
        Log.d("Appointments", "Active appointments: ${list.size}")
    }
}
```

### Fetch Open Slots of a Service
```kotlin
val slots = client.scheduling.slots(
    service = consult.hex,
    start = Instant.parse("2026-10-15T00:00:00Z"),
    end = Instant.parse("2026-10-16T00:00:00Z")
)
```

### Create an Event
```kotlin
val appointment = client.scheduling.create(
    EventRequest(
        title = "Architecture review",
        start = Instant.parse("2026-10-15T18:00:00Z"),
        end = Instant.parse("2026-10-15T18:30:00Z"),
        timezone = "America/New_York"
    )
)
```

### Cancel an Event
```kotlin
client.scheduling.cancel(
    hex = appointment.hex,
    reason = "Client requested reschedule"
)
```

See `docs/services/scheduling.md` for the full surface.
