package pro.aduki.hermes.sync.engine

import io.objectbox.Box
import io.objectbox.BoxStore
import pro.aduki.hermes.core.models.AppointmentRecord
import pro.aduki.hermes.net.http.Scheduling
import pro.aduki.hermes.store.entities.Appointment
import pro.aduki.hermes.store.entities.Outbox
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Schedule synchronizer reconciles local appointments with remote server state.
 */
class Schedule(
    private val box: Box<Appointment>,
    private val outbox: Box<Outbox>,
    private val net: Scheduling
) {
    constructor(store: BoxStore, net: Scheduling) : this(
        box = store.boxFor(Appointment::class.java),
        outbox = store.boxFor(Outbox::class.java),
        net = net
    )

    /**
     * Executes a delta synchronization pulling active appointments from the server.
     */
    fun sync(): Int {
        val records = net.appointments(activeOnly = false)
        val existing = box.all.associateBy { it.hex }
        val toSave = mutableListOf<Appointment>()

        for (rec in records) {
            val local = existing[rec.hex]
            val startMs = parseIso(rec.start)
            val endMs = parseIso(rec.end)

            val appt = Appointment(
                id = local?.id ?: 0L,
                hex = rec.hex,
                service = rec.service,
                host = rec.host,
                start = startMs,
                end = endMs,
                timezone = rec.timezone,
                status = rec.status,
                uid = rec.uid,
                location = rec.location,
                notes = rec.notes,
                updated = System.currentTimeMillis()
            )
            toSave.add(appt)
        }

        box.put(toSave)
        return toSave.size
    }

    /**
     * Optimistically cancels an appointment locally and queues a cancel mutation into the outbox.
     */
    fun cancel(hex: String, reason: String = "") {
        val existing = box.all.firstOrNull { it.hex == hex }
        if (existing != null) {
            existing.status = "cancelled"
            existing.cancelled = System.currentTimeMillis()
            existing.updated = System.currentTimeMillis()
            box.put(existing)
        }

        outbox.put(
            Outbox(
                action = "appointment_cancel",
                payload = JSONObject().put("hex", hex).put("reason", reason).toString().toByteArray(),
                hex = hex,
                created = System.currentTimeMillis()
            )
        )
    }

    private fun parseIso(iso: String): Long {
        return try {
            if (iso.isBlank()) return 0L
            Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(iso)).toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }
}

