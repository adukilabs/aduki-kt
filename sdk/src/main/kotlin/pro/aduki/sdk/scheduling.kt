package pro.aduki.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pro.aduki.core.models.BookRequest
import pro.aduki.core.models.BookingRecord
import pro.aduki.core.models.Availability
import pro.aduki.core.models.CalendarRecord
import pro.aduki.core.models.AppointmentRecord
import pro.aduki.core.models.EventChanges
import pro.aduki.core.models.EventRequest
import pro.aduki.core.models.OfferRecord
import pro.aduki.core.models.OfferRequest
import pro.aduki.core.models.Response
import pro.aduki.core.models.ServiceRecord
import pro.aduki.core.models.ServiceRequest
import pro.aduki.core.models.Slot
import pro.aduki.core.models.Stats
import pro.aduki.net.http.Scheduling as NetScheduling
import pro.aduki.state.repository.Appointment as AppointmentRepo
import pro.aduki.state.repository.Occurrence
import pro.aduki.store.entities.Appointment
import pro.aduki.sync.engine.Schedule as ScheduleEngine
import pro.aduki.sync.outbox.Worker
import java.time.Instant

/**
 * Scheduling is the calendar and booking service (`client.scheduling`).
 *
 * Events ("appointments") live in the local store: every write is applied
 * there at once and queued in the outbox, then sent to `/user/events` as an
 * iCalendar object. Writes throw `IllegalStateException("no local store")`
 * without `Aduki.Builder.store`. Services are the account's booking pages
 * (`/user/pages`) and are read and written online.
 */
class Scheduling internal constructor(
    private val client: Aduki,
    private val repo: AppointmentRepo? = null,
    private val engine: ScheduleEngine? = null,
    private val net: NetScheduling? = null,
    private val worker: Worker? = null
) {
    // Writes need the local store: without one they would vanish silently.
    private fun local(): ScheduleEngine = engine ?: throw IllegalStateException("no local store")

    private fun api(): NetScheduling = net ?: throw IllegalStateException("no scheduling client")

    // Sends what was queued; a failure stays queued for the next flush.
    private suspend fun flush() {
        runCatching { worker?.drain() }
    }

    private val _calendars = MutableStateFlow<List<CalendarRecord>>(emptyList())

    /** The account's calendars, kept up to date by [calendars] and [sync]. */
    val calendars: StateFlow<List<CalendarRecord>> = _calendars.asStateFlow()

    /** Observes the account's calendars. */
    fun observeCalendars(): StateFlow<List<CalendarRecord>> = calendars

    /** Fetches the account's calendars from the server and updates [calendars]. */
    suspend fun calendars(): List<CalendarRecord> = withContext(Dispatchers.IO) {
        val list = api().calendars()
        _calendars.value = list
        list
    }

    /** Resolves a calendar by its [hex], or the default calendar when blank/null. */
    fun calendar(hex: String? = null): CalendarRecord? {
        val target = hex?.trim().orEmpty()
        return if (target.isEmpty()) {
            _calendars.value.find { it.default } ?: _calendars.value.firstOrNull()
        } else {
            _calendars.value.find { it.hex == target }
        }
    }

    /** Resolves the calendar of [appointment]. */
    fun calendar(appointment: Appointment): CalendarRecord? = calendar(appointment.calendar)

    /** Resolves the calendar of [appointment]. */
    fun calendar(appointment: AppointmentRecord): CalendarRecord? = calendar(appointment.calendar)

    /** Creates a new calendar collection on the server. */
    suspend fun createCalendar(
        name: String,
        color: String? = null,
        timezone: String? = null,
        description: String? = null
    ): CalendarRecord = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "calendar name is required" }
        val created = api().createCalendar(name, color, timezone, description)
        _calendars.value = _calendars.value + created
        created
    }

    /** Renames a calendar. */
    suspend fun renameCalendar(hex: String, name: String): CalendarRecord = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "calendar name is required" }
        val updated = api().renameCalendar(hex, name)
        _calendars.value = _calendars.value.map { if (it.hex == hex) updated else it }
        updated
    }

    /** Updates calendar properties. */
    suspend fun updateCalendar(
        hex: String,
        name: String? = null,
        color: String? = null,
        timezone: String? = null,
        description: String? = null,
        busy: Boolean? = null
    ): CalendarRecord = withContext(Dispatchers.IO) {
        val updated = api().updateCalendar(hex, name, color, timezone, description, busy)
        _calendars.value = _calendars.value.map { if (it.hex == hex) updated else it }
        updated
    }

    /** Deletes a calendar and its events from the server. */
    suspend fun removeCalendar(hex: String) = withContext(Dispatchers.IO) {
        api().removeCalendar(hex)
        _calendars.value = _calendars.value.filterNot { it.hex == hex }
    }

    /** Every locally stored event, by start. */
    val appointments: StateFlow<List<Appointment>>?
        get() = repo?.all

    /** Events that start from now on and are not cancelled. */
    val upcoming: Flow<List<Appointment>>?
        get() = repo?.upcoming

    /**
     * The occurrences in `[start, end)`, repeating events expanded, kept up
     * to date as the store changes. Null without a local store.
     */
    fun observe(start: Instant, end: Instant): StateFlow<List<Occurrence>>? =
        repo?.observe(start.toEpochMilli(), end.toEpochMilli())

    /** The occurrences in `[start, end)` now, repeating events expanded. */
    fun expand(start: Instant, end: Instant): List<Occurrence> =
        repo?.all?.value?.let { pro.aduki.state.repository.expand(it, start.toEpochMilli(), end.toEpochMilli()) }
            ?: emptyList()

    /** Loads every event from the server into the store; returns how many changed. */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        flush()
        runCatching {
            net?.calendars()?.let { _calendars.value = it }
        }
        engine?.sync() ?: (net?.events()?.size ?: 0)
    }

    /** The local event with [hex], or null. */
    fun get(hex: String): Appointment? = repo?.get(hex) ?: engine?.get(hex)

    /**
     * Creates an event: personal, all-day, repeating, with attendees and
     * reminders, in no service. It is stored at once with a `local-...` hex
     * that becomes the server's once sent. With [notify], the server
     * invites the attendees (iTIP REQUEST) when the account organizes it.
     */
    suspend fun create(request: EventRequest, notify: Boolean = false): Appointment =
        withContext(Dispatchers.IO) { local().create(request, notify) }.also { flush() }

    /**
     * Edits an event; null fields of [changes] keep their value. With
     * [notify], the server sends the changed event to the attendees.
     */
    suspend fun update(hex: String, changes: EventChanges, notify: Boolean = false): Appointment =
        withContext(Dispatchers.IO) { local().update(hex, changes, notify) }.also { flush() }

    /** Moves an event to [start] .. [end]; [notify] as for [update]. */
    suspend fun reschedule(hex: String, start: Instant, end: Instant, notify: Boolean = false): Appointment =
        withContext(Dispatchers.IO) { local().reschedule(hex, start, end, notify) }.also { flush() }

    /**
     * Answers an invitation (`PARTSTAT` of [address], by default the
     * account's own address) with `POST /user/events/{hex}/reply`: the
     * server stores the answer and sends the organizer an iTIP REPLY with
     * [comment]. For an event without an organizer the answer is only
     * stored.
     */
    suspend fun respond(hex: String, response: Response, address: String? = null, comment: String = ""): Appointment {
        val engine = local()
        val me = address ?: client.me()?.email?.ifBlank { null }
            ?: throw IllegalStateException("the account's address is unknown: pass address")
        return withContext(Dispatchers.IO) { engine.respond(hex, response, me, comment) }.also { flush() }
    }

    /**
     * Cancels an event (`STATUS:CANCELLED`, [reason] as a comment); it stays
     * in the calendar. With [notify], the server sends the cancelled event
     * to the attendees (an iTIP REQUEST with the cancelled status; [remove]
     * with `notify` sends a CANCEL).
     */
    suspend fun cancel(hex: String, reason: String = "", notify: Boolean = false): Appointment =
        withContext(Dispatchers.IO) { local().cancel(hex, reason, notify) }.also { flush() }

    /** Deletes an event; with [notify], the attendees get an iTIP CANCEL when the account organizes it. */
    suspend fun remove(hex: String, notify: Boolean = false) {
        withContext(Dispatchers.IO) { local().remove(hex, notify) }
        flush()
    }

    /**
     * Imports an iCalendar file (`.ics`): each event (by UID) is created, or
     * replaces the stored event with that UID. Parsed on the device: the
     * server takes one event per request.
     */
    suspend fun importIcs(text: String, calendar: String = ""): List<Appointment> =
        withContext(Dispatchers.IO) { local().import(text, calendar) }.also { flush() }

    /** The events with [hexes] (every stored event when null) as one iCalendar file. */
    fun exportIcs(hexes: List<String>? = null): String = local().export(hexes)

    // ── Services (booking pages) ─────────────────────────────────────

    /** The account's services. */
    suspend fun services(): List<ServiceRecord> = withContext(Dispatchers.IO) { api().services() }

    /** One service. */
    suspend fun service(hex: String): ServiceRecord = withContext(Dispatchers.IO) { api().service(hex) }

    /**
     * Creates a service. With [ServiceRequest.windows] it gets its own
     * schedule (named after the slug) open in those windows; without, it
     * uses the account's main schedule.
     */
    suspend fun createService(request: ServiceRequest): ServiceRecord = withContext(Dispatchers.IO) {
        request.validate()
        require(!request.name.isNullOrBlank() && !request.slug.isNullOrBlank()) { "name and slug are required" }
        val api = api()
        val schedule = request.windows?.let { api.createSchedule(request.slug!!, request.zone ?: "UTC", it) }.orEmpty()
        api.createService(request, schedule)
    }

    /**
     * Edits a service; null fields keep their value, `paused = true` pauses
     * it. New [ServiceRequest.windows] replace the rules of the service's
     * schedule (`ServiceRecord.schedule`), else of the schedule named after
     * the slug, else move the service to a new such schedule.
     */
    suspend fun updateService(hex: String, request: ServiceRequest): ServiceRecord = withContext(Dispatchers.IO) {
        request.validate()
        val api = api()
        var schedule = ""
        val windows = request.windows
        if (windows != null) {
            val current = api.service(hex)
            val slug = request.slug ?: current.slug
            val own = current.schedule.ifBlank { null } ?: api.schedules().firstOrNull { it.second == slug }?.first
            if (own != null) api.rules(own, windows) else schedule = api.createSchedule(slug, request.zone ?: "UTC", windows)
        }
        api.editService(hex, request, schedule)
    }

    /** Deletes a service; one that has bookings cannot be deleted (pause it). */
    suspend fun removeService(hex: String) = withContext(Dispatchers.IO) { api().removeService(hex) }

    /**
     * Reads the weekly hours and IANA zone of [service] (service hex, slug, or schedule hex).
     */
    suspend fun availability(service: String): Availability = withContext(Dispatchers.IO) {
        val target = service.trim()
        require(target.isNotEmpty()) { "service is required" }
        val api = api()
        val sched = runCatching { api.service(target).schedule }
            .getOrNull()
            ?.ifBlank { null }
            ?: runCatching {
                api.services().firstOrNull { it.slug.equals(target, ignoreCase = true) }?.schedule
            }.getOrNull()?.ifBlank { null }
            ?: target
        api.schedule(sched)
    }

    /**
     * The public address of a service, `{endpoint}/book/pages/{hex}`: what
     * guests (or a booking web page) read slots from and book on. Pass the
     * web page's base as [base] to link to that page instead.
     */
    fun bookingUrl(service: ServiceRecord, base: String = client.options.endpoint): String =
        "${base.trimEnd('/')}/book/pages/${service.hex}"

    /** Open slots of [service] in `[start, end)` (at most 62 days), for [length] minutes or its default. */
    suspend fun slots(service: String, start: Instant, end: Instant, length: Int? = null): List<Slot> =
        withContext(Dispatchers.IO) { api().slots(service, start, end, length) }

    /** Books a slot of [service] as a guest; the reply carries the guest's manage token. */
    suspend fun book(service: String, request: BookRequest): BookingRecord = withContext(Dispatchers.IO) {
        request.validate()
        api().book(service, request)
    }

    /** Books a slot of [service] as the signed-in account (no guest details needed). */
    suspend fun reserve(service: String, request: BookRequest): BookingRecord = withContext(Dispatchers.IO) {
        request.validate(guest = false)
        api().reserve(service, request)
    }

    /** One booking with its guests. */
    suspend fun booking(hex: String): BookingRecord = withContext(Dispatchers.IO) { api().booking(hex) }

    /** Confirms, cancels or closes a booking: [state] is `confirmed`, `cancelled`, `done` or `noshow`. */
    suspend fun updateBooking(hex: String, state: String): BookingRecord = withContext(Dispatchers.IO) {
        require(state in setOf("confirmed", "cancelled", "done", "noshow")) { "state: confirmed, cancelled, done or noshow" }
        api().updateBooking(hex, state)
    }

    /** The offers (priced or free sub-services) of the account's services, of one when [service] is set. */
    suspend fun offers(service: String = ""): List<OfferRecord> = withContext(Dispatchers.IO) { api().offers(service) }

    /** Creates a priced sub-service (offer) for [service] (service hex or slug). */
    suspend fun createOffer(service: String, request: OfferRequest): OfferRecord = withContext(Dispatchers.IO) {
        request.validate()
        require(!request.name.isNullOrBlank()) { "offer name is required" }
        val target = service.trim()
        require(target.isNotEmpty()) { "service is required" }
        val page = runCatching { api().service(target).hex }.getOrDefault(target)
        api().createOffer(page, request)
    }

    /** Edits an offer; a null field keeps its value. */
    suspend fun updateOffer(hex: String, request: OfferRequest): OfferRecord = withContext(Dispatchers.IO) {
        request.validate()
        val target = hex.trim()
        require(target.isNotEmpty()) { "offer hex is required" }
        api().updateOffer(target, request)
    }

    /** Deletes an offer; 409 Conflict once it has bookings. */
    suspend fun removeOffer(hex: String) = withContext(Dispatchers.IO) {
        val target = hex.trim()
        require(target.isNotEmpty()) { "offer hex is required" }
        api().removeOffer(target)
    }

    /** The bookings of the account's services. */
    suspend fun bookings(upcoming: Boolean = false): List<BookingRecord> =
        withContext(Dispatchers.IO) { api().bookings(upcoming) }

    /**
     * Booking counts of [service] by state for bookings starting in
     * `[start, end)` (at most a year), and its page views on the days the
     * range touches (`GET /user/pages/{hex}/stats`).
     */
    suspend fun stats(service: String, start: Instant, end: Instant): Stats =
        withContext(Dispatchers.IO) { api().stats(service, start, end) }
}
