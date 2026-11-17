package pro.aduki.state.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pro.aduki.core.calendar.Events
import pro.aduki.core.calendar.Recurrence
import pro.aduki.store.entities.Appointment as AppointmentEntity
import pro.aduki.store.entities.Appointment_

/**
 * AppointmentSource abstracts reactive entity streams for appointments.
 */
interface AppointmentSource {
    fun appointments(): Flow<List<AppointmentEntity>>
    fun list(): List<AppointmentEntity> = emptyList()
    fun get(hex: String): AppointmentEntity?
}

/**
 * Appointment repository exposing reactive calendar StateFlow query streams.
 */
class Appointment(
    private val source: AppointmentSource,
    private val scope: CoroutineScope
) {
    constructor(store: BoxStore, scope: CoroutineScope) : this(
        source = object : AppointmentSource {
            private val box = store.boxFor(AppointmentEntity::class.java)

            override fun appointments(): Flow<List<AppointmentEntity>> = callbackFlow {
                val query = box.query().build()
                val sub = query.subscribe().observer { data ->
                    trySend(data)
                }
                awaitClose { sub.cancel() }
            }

            override fun list(): List<AppointmentEntity> = box.all

            override fun get(hex: String): AppointmentEntity? =
                box.query(Appointment_.hex.equal(hex)).build().use { it.findFirst() }
        },
        scope = scope
    )

    private val _appointments = MutableStateFlow(source.list().sortedBy { it.start })
    private var observing = false

    private fun ensureObserving() {
        if (observing) return
        observing = true
        scope.launch {
            source.appointments().collect { list ->
                _appointments.value = list.sortedBy { it.start }
            }
        }
    }

    /**
     * Observable stream of all appointments ordered chronologically.
     */
    val all: StateFlow<List<AppointmentEntity>>
        get() {
            ensureObserving()
            return _appointments
        }

    /**
     * Refreshes the in-memory stream synchronously from [AppointmentSource.list].
     */
    fun refresh() {
        _appointments.value = source.list().sortedBy { it.start }
    }

    /**
     * Synchronous snapshot of all appointments.
     */
    fun list(): List<AppointmentEntity> =
        source.list().ifEmpty { all.value }.sortedBy { it.start }

    /**
     * Observable stream of upcoming appointments (start >= current time).
     */
    val upcoming: Flow<List<AppointmentEntity>>
        get() = all.map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.start >= now && it.status != "cancelled" }
        }

    /**
     * The occurrences overlapping `[start, end)` (epoch millis), repeating
     * events expanded (RRULE less EXDATE, in the event's zone), ordered by
     * start. Cancelled events are left out unless [cancelled].
     */
    fun observe(start: Long, end: Long, cancelled: Boolean = false): StateFlow<List<Occurrence>> =
        all
            .map { list -> expand(list, start, end, cancelled) }
            .stateIn(scope, SharingStarted.Eagerly, expand(all.value, start, end, cancelled))

    /**
     * Resolves appointment entity by hex identifier.
     */
    fun get(hex: String): AppointmentEntity? = source.get(hex)
}


/**
 * Occurrence is one occurrence of an event: [appointment] itself for a
 * single event, or one of its repeats, at [start] .. [end] (epoch millis).
 */
data class Occurrence(
    val appointment: AppointmentEntity,
    val start: Long,
    val end: Long
)

/** The occurrences of [list] overlapping `[start, end)`, by start. */
fun expand(list: List<AppointmentEntity>, start: Long, end: Long, cancelled: Boolean = false): List<Occurrence> =
    list.asSequence()
        .filter { cancelled || it.status != "cancelled" }
        .flatMap { a -> occurrences(a, start, end, cancelled) }
        .sortedBy { it.start }
        .toList()

private fun occurrences(a: AppointmentEntity, start: Long, end: Long, cancelled: Boolean): List<Occurrence> {
    val duration = maxOf(0L, a.end - a.start)
    fun overlaps(s: Long, e: Long) = if (e == s) s in start until end else s < end && e > start

    val overrides = if (a.ical.isNotBlank()) Events.overrides(a.ical) else emptyList()
    val overrideMap = overrides.associateBy { it.recurrenceId }
    val excluded = (a.excluded() + overrideMap.keys).toSet()

    val out = mutableListOf<Occurrence>()

    if (a.rrule.isNotBlank()) {
        val baseStarts = Recurrence.expand(a.start, a.end, a.rrule, start, end, a.timezone, a.allday, excluded.toList())
        for (s in baseStarts) {
            val e = s + duration
            out.add(Occurrence(a, s, e))
        }
    } else if (overrideMap.isEmpty()) {
        if (overlaps(a.start, a.end)) {
            out.add(Occurrence(a, a.start, a.end))
        }
    }

    for ((_, ov) in overrideMap) {
        if (ov.cancelled && !cancelled) continue
        val ovStart = ov.event.start
        val ovEnd = ov.event.end
        if (overlaps(ovStart, ovEnd)) {
            val ovAppt = a.copy(
                title = ov.event.title.ifBlank { a.title },
                location = ov.event.location.ifBlank { a.location },
                notes = ov.event.notes.ifBlank { a.notes },
                start = ovStart,
                end = ovEnd,
                status = if (ov.event.status.isNotBlank()) ov.event.status.lowercase() else a.status,
                allday = ov.event.allday,
                timezone = ov.event.timezone
            )
            out.add(Occurrence(ovAppt, ovStart, ovEnd))
        }
    }

    return out
}
