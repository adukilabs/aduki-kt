package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pro.aduki.core.calendar.Events
import pro.aduki.core.errors.AdukiException
import pro.aduki.net.http.Scheduling
import pro.aduki.store.entities.Appointment
import pro.aduki.store.entities.Outbox
import pro.aduki.sync.engine.Schedule
import pro.aduki.sync.outbox.Dispatcher
import pro.aduki.sync.outbox.Rejected

/**
 * ScheduleDispatcher sends the calendar actions [Schedule] journals:
 *
 * - `event_create`: `POST /user/events` `{ical, calendar?, notify?}`; the local
 *   `local-...` hex is then replaced by the server's. A `409` (the UID is
 *   already there, e.g. after a lost reply) completes it: the next sync
 *   adopts the server's copy by UID.
 * - `event_update`: `PATCH /user/events/{hex}` `{ical, notify?}`.
 * - `event_reply`: `POST /user/events/{hex}/reply` `{partstat, comment?}`:
 *   the server sets the answer and sends the organizer an iTIP REPLY. An
 *   event the server will not answer for (no organizer, or the account is
 *   not among its attendees: `422`) gets the answered object with
 *   `PATCH /user/events/{hex}` instead, which stores it but tells nobody.
 * - `event_delete`: `DELETE /user/events/{hex}?notify=`.
 * - `appointment_cancel` (journaled by 0.4): sent as an update with
 *   `STATUS:CANCELLED`.
 *
 * An event created offline is addressed by UID until its create was sent.
 * Anything else goes to [fallback]. A 4xx the client cannot fix by retrying
 * (not 408, 409 or 429) is thrown as [Rejected]; a 404 on update or delete
 * completes the action (the event is gone).
 */
class ScheduleDispatcher(
    private val api: Scheduling,
    private val engine: Schedule,
    private val fallback: Dispatcher? = null
) : Dispatcher {

    override suspend fun dispatch(action: Outbox) {
        if (action.action !in Schedule.ACTIONS) {
            val next = fallback ?: throw Rejected("No dispatcher for outbox action '${action.action}'")
            next.dispatch(action)
            return
        }
        val payload = runCatching { JSONObject(String(action.payload, Charsets.UTF_8)) }
            .getOrElse { throw Rejected("Outbox ${action.action} ${action.id} has no JSON payload") }
        withContext(Dispatchers.IO) {
            try {
                when (action.action) {
                    Schedule.CREATE -> create(payload)
                    Schedule.UPDATE -> api.replaceEvent(hex(payload), payload.getString("ical"), payload.optBoolean("notify"))
                    Schedule.REPLY -> reply(payload)
                    Schedule.DELETE -> api.removeEvent(hex(payload), payload.optBoolean("notify"))
                    Schedule.LEGACY -> legacy(payload)
                }
            } catch (e: AdukiException.Network) {
                val code = e.code ?: throw e
                if (code == 404 && action.action != Schedule.CREATE) return@withContext
                if (code == 409 && action.action == Schedule.CREATE) return@withContext
                if (code in 400..499 && code !in RETRYABLE) {
                    throw Rejected("Aduki rejected '${action.action}' (HTTP $code)", code, e)
                }
                throw e
            }
        }
    }

    private fun create(payload: JSONObject) {
        val made = api.createEvent(payload.getString("ical"), payload.optString("calendar"), payload.optBoolean("notify"))
        engine.created(payload.optString("hex"), made)
    }

    // The server hex: a `local-` hex is looked up by UID once its create went out.
    private fun hex(payload: JSONObject): String {
        val hex = payload.optString("hex")
        if (!hex.startsWith(Appointment.LOCAL)) return hex
        val now = engine.resolve(payload.optString("uid")) ?: hex
        if (now.startsWith(Appointment.LOCAL)) throw Rejected("Event ${payload.optString("uid")} was never created")
        return now
    }

    private fun reply(payload: JSONObject) {
        val hex = hex(payload)
        try {
            api.reply(hex, payload.getString("partstat"), payload.optString("comment").ifBlank { null })
        } catch (e: AdukiException.Network) {
            if (e.code != 422) throw e
            api.replaceEvent(hex, payload.getString("ical"))
        }
    }

    private fun legacy(payload: JSONObject) {
        val hex = payload.optString("hex")
        val row = engine.get(hex) ?: return
        if (row.ical.isBlank()) throw Rejected("Event $hex has no iCalendar object to cancel")
        api.replaceEvent(hex, Events.cancel(row.ical, payload.optString("reason")))
    }

    private companion object {
        val RETRYABLE = setOf(408, 409, 429)
    }
}
