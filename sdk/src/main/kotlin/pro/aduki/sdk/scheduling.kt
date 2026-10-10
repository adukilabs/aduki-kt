package pro.aduki.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import pro.aduki.core.models.AppointmentRecord
import pro.aduki.core.models.ServiceRecord
import pro.aduki.core.models.Slot
import pro.aduki.net.http.Scheduling as NetScheduling
import pro.aduki.state.repository.Appointment as AppointmentRepo
import pro.aduki.store.entities.Appointment
import pro.aduki.sync.engine.Schedule as ScheduleEngine

/**
 * Scheduling service providing high-level operations for appointments, services, availability, and bookings.
 */
class Scheduling internal constructor(
    private val client: Aduki,
    private val repo: AppointmentRepo? = null,
    private val engine: ScheduleEngine? = null,
    private val net: NetScheduling? = null
) {
    /**
     * Observable stream of all locally cached appointments ordered chronologically.
     */
    val appointments: StateFlow<List<Appointment>>?
        get() = repo?.all

    /**
     * Observable stream of upcoming appointments (start >= current time).
     */
    val upcoming: Flow<List<Appointment>>?
        get() = repo?.upcoming

    /**
     * Synchronizes active appointments with the remote server.
     */
    suspend fun sync(): Int {
        return engine?.sync() ?: (net?.appointments()?.size ?: 0)
    }

    /**
     * Resolves appointment by hex identifier from local store.
     */
    fun get(hex: String): Appointment? {
        return repo?.get(hex)
    }

    /**
     * Creates a new scheduled appointment on the server and syncs.
     */
    suspend fun create(
        service: String,
        start: String,
        end: String,
        timezone: String = "UTC",
        notes: String = "",
        location: String = ""
    ): AppointmentRecord? {
        val appt = net?.create(service, start, end, timezone, notes, location)
        engine?.sync()
        return appt
    }

    /**
     * Cancels an appointment.
     */
    suspend fun cancel(hex: String, reason: String = ""): Boolean {
        engine?.cancel(hex, reason)
        return net?.cancel(hex, reason) ?: true
    }

    /**
     * Computes available booking slots for a given service within [start, end].
     */
    suspend fun slots(start: String, end: String, service: String = ""): List<Slot> {
        return net?.availability(start, end, service) ?: emptyList()
    }

    /**
     * Lists active booking services / event templates.
     */
    suspend fun services(): List<ServiceRecord> {
        return net?.services() ?: emptyList()
    }

    /**
     * Public guest booking flow using service slug.
     */
    suspend fun book(
        slug: String,
        start: String,
        end: String,
        guestName: String,
        guestEmail: String,
        answers: Map<String, String> = emptyMap()
    ): AppointmentRecord? {
        return net?.book(slug, start, end, guestName, guestEmail, answers)
    }
}

