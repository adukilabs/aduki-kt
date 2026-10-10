# Aduki Android Kotlin SDK Scheduling Engine

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win.

This guide details the scheduling and appointment subsystem integrated into the Aduki Android Kotlin SDK.

---

## 1. Subsystem Modules

The scheduling capability is distributed across the SDK modules following clean separation of concerns:

- **`:store`**: ObjectBox FlatBuffers models (`Appointment`, `Service`) and memory-efficient `Slot` models.
- **`:net`**: Pure OkHttp JSON client executing `/user/appointments`, `/user/services`, `/user/availability`, and public `/book/{slug}` requests.
- **`:sync`**: Delta synchronizer updating local appointments cache with local ID retention, and enqueuing offline booking mutations in the outbox.
- **`:state`**: Reactive `AppointmentRepository` exposing hot `StateFlow<List<Appointment>>` feeds via live ObjectBox query observers.
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

### Fetch Free / Busy Slots
```kotlin
val slots = client.scheduling.slots(
    service = "srv_consultation",
    start = "2026-10-15T09:00:00",
    end = "2026-10-15T17:00:00"
)
```

### Book Appointment (Host or Authenticated User)
```kotlin
val appointment = client.scheduling.create(
    service = "srv_consultation",
    start = "2026-10-15T14:00:00",
    end = "2026-10-15T14:30:00",
    timezone = "America/New_York",
    notes = "Architecture review"
)
```

### Cancel Appointment
```kotlin
client.scheduling.cancel(
    hex = appointment.hex,
    reason = "Client requested reschedule"
)
```

