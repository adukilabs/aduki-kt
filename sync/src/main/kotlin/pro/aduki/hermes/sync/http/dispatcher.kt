package pro.aduki.hermes.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.hermes.core.errors.HermesException
import pro.aduki.hermes.net.http.Mail
import pro.aduki.hermes.store.entities.Message
import pro.aduki.hermes.store.entities.Outbox
import pro.aduki.hermes.sync.outbox.Dispatcher
import pro.aduki.hermes.sync.outbox.Rejected

/**
 * HttpDispatcher sends outbox actions to the Hermes REST API.
 *
 * - `send`: `POST /user/mail/send` with a stable `Idempotency-Key` per
 *   outbox entry, so a retry after a lost response replays the first result
 *   instead of sending twice. The local placeholder id is then replaced by
 *   the server's via [rename].
 * - `flag`: `PATCH /user/mail/{hex}/flags`, adding or removing one flag.
 * - `move`: `PATCH /user/mail/{hex}/mailbox`.
 * - `delete`: `DELETE /user/mail/{hex}`.
 *
 * Anything else goes to [fallback]. A 4xx the client cannot fix by retrying
 * (not 408, 409 or 429) is thrown as [Rejected], so the queue moves on. For
 * flag/move/delete of a message that no longer exists (404), there is
 * nothing left to do and the action completes.
 */
class HttpDispatcher(
    private val api: Mail,
    /** The local message with this id (the placeholder for sends). */
    private val lookup: (String) -> Message? = { null },
    /** Replace a local placeholder id with the server's. */
    private val rename: (old: String, new: String) -> Unit = { _, _ -> },
    private val fallback: Dispatcher? = null
) : Dispatcher {

    override suspend fun dispatch(action: Outbox) {
        if (action.action !in MAIL) {
            val next = fallback ?: throw Rejected("No dispatcher for outbox action '${action.action}'")
            next.dispatch(action)
            return
        }
        withContext(Dispatchers.IO) {
            try {
                when (action.action) {
                    "send" -> send(action)
                    "flag" -> flag(action)
                    "move" -> move(action)
                    "delete" -> api.delete(hexOf(action))
                }
            } catch (e: HermesException.Network) {
                val code = e.code ?: throw e
                if (code == 404 && action.action != "send") return@withContext
                if (code in 400..499 && code !in RETRYABLE) {
                    throw Rejected("Hermes rejected '${action.action}' (HTTP $code)", code, e)
                }
                throw e
            }
        }
    }

    private fun send(action: Outbox) {
        val text = String(action.payload, Charsets.UTF_8)
        val local = lookup(action.hex)
        val (to, cc, subject, body, from) = if (text.trimStart().startsWith("{")) {
            val obj = JSONObject(text)
            Request(
                strings(obj.optJSONArray("to")),
                strings(obj.optJSONArray("cc")),
                obj.optString("subject"),
                obj.optString("text"),
                obj.optString("from").ifBlank { null }
            )
        } else {
            // 0.1.x journaled only the body; recipients live on the message.
            Request(local?.recipients() ?: emptyList(), emptyList(), local?.subject ?: "", text, null)
        }
        if (to.isEmpty()) {
            throw Rejected("Outbox send ${action.id} has no recipients")
        }
        val hex = api.send(to, subject, body, cc, from, idempotencyKey(action))
        rename(action.hex, hex)
    }

    private fun flag(action: Outbox) {
        val text = String(action.payload, Charsets.UTF_8)
        val (hex, bit, set) = if (text.trimStart().startsWith("{")) {
            val obj = JSONObject(text)
            Triple(obj.getString("hex"), obj.getInt("flag"), obj.getBoolean("set"))
        } else {
            // 0.1.x: "hex:bit" toggles; the local message holds the result.
            val hex = text.substringBefore(":")
            val bit = text.substringAfter(":").toInt()
            val local = lookup(hex) ?: return
            Triple(hex, bit, (local.flags and bit) != 0)
        }
        val name = Message.flagName(bit) ?: throw Rejected("Unknown flag bit $bit")
        if (set) api.flags(hex, add = listOf(name)) else api.flags(hex, remove = listOf(name))
    }

    private fun move(action: Outbox) {
        val text = String(action.payload, Charsets.UTF_8)
        if (text.trimStart().startsWith("{")) {
            val obj = JSONObject(text)
            api.move(obj.getString("hex"), obj.getString("mailbox"))
        } else {
            api.move(text.substringBefore(":"), text.substringAfter(":"))
        }
    }

    private fun hexOf(action: Outbox): String {
        val text = String(action.payload, Charsets.UTF_8)
        return if (text.trimStart().startsWith("{")) JSONObject(text).getString("hex") else action.hex.ifBlank { text }
    }

    private data class Request(
        val to: List<String>,
        val cc: List<String>,
        val subject: String,
        val text: String,
        val from: String?
    )

    companion object {
        private val MAIL = setOf("send", "flag", "move", "delete")
        private val RETRYABLE = setOf(408, 409, 429)

        /** Stable across retries and restarts of the same outbox entry. */
        fun idempotencyKey(action: Outbox): String = "outbox-${action.id}-${action.created}"

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
    }
}
