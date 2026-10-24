package pro.aduki.state.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import pro.aduki.core.calendar.Recurrence
import pro.aduki.store.entities.Appointment as AppointmentEntity
import pro.aduki.store.entities.Appointment_

/**
 * AppointmentSource abstracts reactive entity streams for appointments.
 */
interface AppointmentSource {
    fun appointments(): Flow<List<AppointmentEntity>>
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

            override fun get(hex: String): AppointmentEntity? =
                box.query(Appointment_.hex.equal(hex)).build().use { it.findFirst() }
        },
        scope = scope
    )

    private val appointmentsFlow: StateFlow<List<AppointmentEntity>> by lazy {
        source.appointments()
            .map { list -> list.sortedBy { it.start } }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )
    }

    /**
     * Observable stream of all appointments ordered chronologically.
     */
    val all: StateFlow<List<AppointmentEntity>>
        get() = appointmentsFlow

    /**
     * Observable stream of upcoming appointments (start >= current time).
     */
    val upcoming: Flow<List<AppointmentEntity>>
        get() = appointmentsFlow.map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.start >= now && it.status != "cancelled" }
        }

    /**
     * The occurrences overlapping `[start, end)` (epoch millis), repeating
     * events expanded (RRULE less EXDATE, in the event's zone), ordered by
     * start. Cancelled events are left out unless [cancelled].
     */
    fun observe(start: Long, end: Long, cancelled: Boolean = false): StateFlow<List<Occurrence>> =
        appointmentsFlow
            .map { list -> expand(list, start, end, cancelled) }
            .stateIn(scope, SharingStarted.Eagerly, expand(appointmentsFlow.value, start, end, cancelled))

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
        .flatMap { a ->
            Recurrence.expand(a.start, a.end, a.rrule, start, end, a.timezone, a.allday, a.excluded())
                .map { s -> Occurrence(a, s, s + (a.end - a.start)) }
        }
        .sortedBy { it.start }
        .toList()
