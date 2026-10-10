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
import pro.aduki.store.entities.Appointment as AppointmentEntity

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
                box.all.firstOrNull { it.hex == hex }
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
     * Resolves appointment entity by hex identifier.
     */
    fun get(hex: String): AppointmentEntity? = source.get(hex)
}

