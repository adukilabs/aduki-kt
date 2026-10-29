package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.net.http.Jmap
import pro.aduki.net.http.Mail
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox
import pro.aduki.sync.outbox.Dispatcher
import pro.aduki.sync.outbox.Rejected

/**
 * HttpDispatcher sends outbox actions to the Aduki REST API.
 *
 * - `compose`: a [Draft][pro.aduki.core.models.Draft] sent with JMAP by
 *   [Composer] (bcc, HTML, attachments, replies), keyed for retries like `send`.
 * - `send`: `POST /user/mail/send` with a stable `Idempotency-Key` per
 *   outbox entry, so a retry after a lost response replays the first result
 *   instead of sending twice. The local placeholder id is then replaced by
 *   the server's via [rename].
 * - `flag`: `PATCH /user/mail/{hex}/flags`, adding or removing one flag.
 * - `move`: `PATCH /user/mail/{hex}/mailbox`.
 * - `delete`: `DELETE /user/mail/{hex}`.
 * - `flags`, `moves`: one JMAP `Email/set` per 500 messages when [jmap] is
 *   given (`\Deleted` has no JMAP keyword and always goes over REST), else
 *   the REST call per message.
 * - `deletes`: `DELETE /user/mail/{hex}` per message.
 * - `keywords`: `PATCH /user/mail/{hex}/flags` with the keywords to add and remove.
 * - `mailbox_create`: `POST /user/mailbox`, then [adopt] gives the local
 *   placeholder the server's hex. `mailbox_rename`: `PATCH /user/mailbox/{hex}/name`.
 *   `mailbox_delete`: `DELETE /user/mailbox/{hex}`.
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
    private val fallback: Dispatcher? = null,
    /** Batches the bulk actions; without it they go message by message over REST. */
    private val jmap: Jmap? = null,
    /** Replace a mailbox placeholder hex with the server's. */
    private val adopt: (old: String, new: String) -> Unit = { _, _ -> }
) : Dispatcher {

    private val composer: Composer? = jmap?.let(::Composer)

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
                    "compose" -> {
                        val draft = Composer.decode(JSONObject(String(action.payload, Charsets.UTF_8)))
                        val sender = composer ?: throw Rejected("Sending a rich draft needs JMAP")
                        rename(action.hex, sender.send(draft, idempotencyKey(action)))
                    }
                    "flag" -> flag(action)
                    "move" -> move(action)
                    "delete" -> api.delete(hexOf(action))
                    "flags" -> flags(JSONObject(String(action.payload, Charsets.UTF_8)))
                    "moves" -> moves(JSONObject(String(action.payload, Charsets.UTF_8)))
                    "mailbox_create" -> {
                        val obj = JSONObject(String(action.payload, Charsets.UTF_8))
                        val made = api.createMailbox(obj.getString("name"), obj.optString("parent").ifBlank { null })
                        adopt(obj.getString("hex"), made.hex)
                    }
                    "mailbox_rename" -> {
                        val obj = JSONObject(String(action.payload, Charsets.UTF_8))
                        api.renameMailbox(obj.getString("hex"), obj.getString("name"))
                    }
                    "mailbox_move" -> {
                        val obj = JSONObject(String(action.payload, Charsets.UTF_8))
                        val hex = obj.getString("hex")
                        val parent = obj.optString("parent").ifBlank { null }
                        if (jmap != null) {
                            val update = JSONObject().put(hex, JSONObject().put("parentId", parent ?: JSONObject.NULL))
                            val reply = jmap.call("Mailbox/set", JSONObject().put("update", update))
                            val failed = reply.optJSONObject("notUpdated")
                            if (failed != null && failed.has(hex)) {
                                val err = failed.getJSONObject(hex)
                                throw Rejected("Mailbox move refused: ${err.optString("type")}")
                            }
                        } else {
                            api.moveMailbox(hex, parent)
                        }
                    }
                    "mailbox_delete" -> api.deleteMailbox(hexOf(action))
                    "draft_save" -> {
                        val obj = JSONObject(String(action.payload, Charsets.UTF_8))
                        val placeholderHex = obj.getString("hex")
                        val replace = obj.optString("replace").ifBlank { null }
                        val mailbox = obj.getString("mailbox")
                        if (jmap != null) {
                            val createObj = JSONObject().apply {
                                put("mailboxIds", JSONObject().put(mailbox, true))
                                put("keywords", JSONObject().put("\$draft", true))
                                put("subject", obj.optString("subject"))
                                val to = obj.optJSONArray("to")
                                if (to != null && to.length() > 0) {
                                    val toArr = JSONArray()
                                    for (i in 0 until to.length()) {
                                        toArr.put(JSONObject().put("email", to.getString(i)))
                                    }
                                    put("to", toArr)
                                }
                                val text = obj.optString("text")
                                put("bodyValues", JSONObject().put("1", JSONObject().put("value", text)))
                                put("textBody", JSONArray().put(JSONObject().put("partId", "1").put("type", "text/plain")))
                            }
                            val setArg = JSONObject().put("create", JSONObject().put("draft1", createObj))
                            if (replace != null) {
                                setArg.put("destroy", JSONArray().put(replace))
                            }
                            val reply = jmap.call("Email/set", setArg)
                            val created = reply.optJSONObject("created")?.optJSONObject("draft1")
                            val newHex = created?.optString("id")
                            if (!newHex.isNullOrBlank()) {
                                adopt(placeholderHex, newHex)
                            }
                        }
                    }
                    "keywords" -> {
                        val obj = JSONObject(String(action.payload, Charsets.UTF_8))
                        api.flags(obj.getString("hex"), strings(obj.optJSONArray("add")), strings(obj.optJSONArray("remove")))
                    }
                    "deletes" -> each(strings(JSONObject(String(action.payload, Charsets.UTF_8)).optJSONArray("hexes"))) { api.delete(it) }
                }
            } catch (e: AdukiException.Api) {
                // A JMAP method error: only a server failure is worth retrying.
                if (e.kind in TRANSIENT) throw e
                throw Rejected("Aduki rejected '${action.action}' (${e.kind})", e.status, e)
            } catch (e: AdukiException.Network) {
                val code = e.code ?: throw e
                if (code == 404 && action.action !in CREATES) return@withContext
                if (code in 400..499 && code !in RETRYABLE) {
                    throw Rejected("Aduki rejected '${action.action}' (HTTP $code)", code, e)
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

    private fun flags(obj: JSONObject) {
        val hexes = strings(obj.optJSONArray("hexes"))
        val bit = obj.getInt("flag")
        val set = obj.getBoolean("set")
        val keyword = KEYWORDS[bit]
        if (jmap != null && keyword != null) {
            update(hexes) { JSONObject().put("keywords/$keyword", if (set) true else JSONObject.NULL) }
            return
        }
        val name = Message.flagName(bit) ?: throw Rejected("Unknown flag bit $bit")
        each(hexes) { if (set) api.flags(it, add = listOf(name)) else api.flags(it, remove = listOf(name)) }
    }

    private fun moves(obj: JSONObject) {
        val hexes = strings(obj.optJSONArray("hexes"))
        val dest = obj.getString("mailbox")
        if (jmap != null) {
            update(hexes) { JSONObject().put("mailboxIds", JSONObject().put(dest, true)) }
            return
        }
        each(hexes) { api.move(it, dest) }
    }

    // One `Email/set` update per chunk; a message gone from the server is done.
    private fun update(hexes: List<String>, patch: () -> JSONObject) {
        val client = jmap ?: return
        for (chunk in hexes.chunked(CHUNK)) {
            val update = JSONObject()
            chunk.forEach { update.put(it, patch()) }
            val reply = client.call("Email/set", JSONObject().put("update", update))
            val failed = reply.optJSONObject("notUpdated") ?: continue
            val errors = failed.keySet().map { failed.getJSONObject(it).optString("type") }.filter { it != "notFound" }
            if (errors.isNotEmpty()) throw Rejected("Aduki refused Email/set: ${errors.distinct().joinToString()}")
        }
    }

    // A REST call per message; a message already gone (404) is done.
    private fun each(hexes: List<String>, call: (String) -> Unit) {
        for (hex in hexes) {
            try {
                call(hex)
            } catch (e: AdukiException.Network) {
                if (e.code != 404) throw e
            }
        }
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
        private val MAIL = setOf(
            "send", "compose", "flag", "move", "delete", "flags", "moves", "deletes",
            "mailbox_create", "mailbox_rename", "mailbox_delete", "keywords"
        )
        private const val CHUNK = 500
        private val CREATES = setOf("send", "compose", "mailbox_create")
        private val TRANSIENT = setOf("serverFail", "serverUnavailable", "serverPartialFail")

        /** JMAP keyword of each flag bit that has one. */
        private val KEYWORDS = mapOf(
            Message.SEEN to "\$seen",
            Message.ANSWERED to "\$answered",
            Message.FLAGGED to "\$flagged",
            Message.DRAFT to "\$draft"
        )
        private val RETRYABLE = setOf(408, 409, 429)

        /** Stable across retries and restarts of the same outbox entry. */
        fun idempotencyKey(action: Outbox): String = "outbox-${action.id}-${action.created}"

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
    }
}
