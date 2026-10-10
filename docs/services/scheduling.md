# Scheduling Service Reference

The `Scheduling` service (`client.scheduling`) provides high-level operations for appointments, meeting templates, slot availability computation, and public booking.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Scheduling internal constructor(...) {
    val appointments: StateFlow<List<Appointment>>?
    val upcoming: Flow<List<Appointment>>?

    suspend fun sync(): Int
    fun get(hex: String): Appointment?

    suspend fun create(
        service: String,
        start: String,
        end: String,
        timezone: String = "UTC",
        notes: String = "",
        location: String = ""
    ): AppointmentRecord?

    suspend fun cancel(hex: String, reason: String = ""): Boolean
    suspend fun slots(start: String, end: String, service: String = ""): List<Slot>
    suspend fun services(): List<ServiceRecord>

    suspend fun book(
        slug: String,
        start: String,
        end: String,
        guestName: String,
        guestEmail: String,
        answers: Map<String, String> = emptyMap()
    ): AppointmentRecord?
}
```

---

## 2. Usage Examples

### Reactive Appointment Stream

Bind directly to Jetpack Compose or Kotlin ViewModel:

```kotlin
// In ViewModel
val upcomingAppointments: StateFlow<List<Appointment>> = 
    client.scheduling.upcoming
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        ?: MutableStateFlow(emptyList())

// In Compose
@Composable
fun AppointmentsScreen(viewModel: SchedulingViewModel) {
    val appointments by viewModel.upcomingAppointments.collectAsState()

    LazyColumn {
        items(appointments) { appt ->
            AppointmentCard(
                title = appt.notes.ifBlank { "Meeting" },
                time = formatTime(appt.start),
                status = appt.status
            )
        }
    }
}
```

### Free / Busy Availability Lookups

Compute available booking slots across a specific date window:

```kotlin
val slots = client.scheduling.slots(
    service = "srv_consultation",
    start = "2026-10-15T09:00:00Z",
    end = "2026-10-15T17:00:00Z"
)

slots.forEach { slot ->
    println("Bookable window: ${slot.start} -> ${slot.end}")
}
```

### Public Guest Booking

Book an appointment on a public slug without authentication:

```kotlin
val booking = client.scheduling.book(
    slug = "architecture-consult",
    start = "2026-10-15T14:00:00Z",
    end = "2026-10-15T14:30:00Z",
    guestName = "Jane Doe",
    guestEmail = "jane@customer.com",
    answers = mapOf("company" to "Acme Corp")
)
```

