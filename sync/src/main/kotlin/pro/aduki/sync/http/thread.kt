package pro.aduki.sync.http

import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.errors.Faults
import pro.aduki.core.models.AuthResult
import pro.aduki.core.models.Confidence
import pro.aduki.core.models.Forwarded
import pro.aduki.core.models.Person
import pro.aduki.core.models.Reply
import pro.aduki.core.models.ReplySource
import pro.aduki.core.models.Security
import pro.aduki.core.models.Transport
import pro.aduki.core.thread.Entry
import pro.aduki.net.http.Jmap
import java.time.Instant
import java.time.OffsetDateTime

/**
 * JmapThreadTransport reads the emails of a thread for `Mail.conversation`:
 * `Thread/get` and `Email/get` with the threading headers, and, when the
 * server advertises them, the `aduki:reply` and `aduki:security` properties
 * of [Jmap.READING]. Bodies are read separately ([JmapContentTransport]).
 *
 * Calls are blocking; run them off the main thread.
 */
class JmapThreadTransport(private val jmap: Jmap) {

    /**
     * The reading properties the server serves ([Jmap.REPLY], [Jmap.SECURITY]):
     * those listed in the [Jmap.READING] capability's `properties`. Empty
     * when the capability or the list is missing.
     */
    fun extras(): Set<String> {
        val session = jmap.session()
        if (Jmap.READING !in session.capabilities) return emptySet()
        return session.properties[Jmap.READING].orEmpty().intersect(setOf(Jmap.REPLY, Jmap.SECURITY))
    }

    /**
     * The emails of [threadId] without bodies, reply or security, in one
     * request (`Thread/get`, `Email/get` by back-reference, `Mailbox/get` for
     * the Sent role). Throws [AdukiException.Api] (404) when the server does
     * not know the thread.
     */
    fun emails(threadId: String): List<Entry> {
        val account = jmap.account()
        val calls = listOf(
            Jmap.Invocation("Thread/get", JSONObject().put("accountId", account).put("ids", JSONArray().put(threadId)), "t"),
            Jmap.Invocation(
                "Email/get",
                JSONObject().put("accountId", account)
                    .put("#ids", JSONObject().put("resultOf", "t").put("name", "Thread/get").put("path", "/list/*/emailIds"))
                    .put("properties", JSONArray(PROPERTIES)),
                "e"
            ),
            Jmap.Invocation("Mailbox/get", JSONObject().put("accountId", account).put("ids", JSONObject.NULL).put("properties", JSONArray(listOf("id", "role"))), "b")
        )
        val replies = jmap.call(calls)
        val thread = replies.firstOrNull { it.id == "t" }?.args
        val ids = thread?.optJSONArray("list")?.optJSONObject(0)?.optJSONArray("emailIds")
        if (ids == null || ids.length() == 0) throw AdukiException.Api(404, "entity.missing", "thread $threadId not found")
        val sent = replies.firstOrNull { it.id == "b" }?.args?.optJSONArray("list")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("role").equals("sent", ignoreCase = true) }
                .map { it.optString("id") }
                .toSet()
        } ?: emptySet()
        val list = replies.firstOrNull { it.id == "e" }?.args?.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { entry(it, sent) }
    }

    /**
     * The server's reply and security facts of [hexes], by hex; only the
     * properties in [extras] are asked for. Empty when the server serves
     * neither.
     */
    fun readings(hexes: List<String>, extras: Set<String> = extras()): Map<String, Reading> {
        if (hexes.isEmpty() || extras.isEmpty()) return emptyMap()
        val out = HashMap<String, Reading>()
        for (chunk in hexes.chunked(BATCH)) {
            val args = JSONObject().put("ids", JSONArray(chunk)).put("properties", JSONArray(listOf("id") + extras))
            val list = jmap.call("Email/get", args, Jmap.USING + Jmap.READING).optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val email = list.optJSONObject(i) ?: continue
                val hex = email.optString("id").ifBlank { null } ?: continue
                out[hex] = Reading(
                    reply = if (Jmap.REPLY in extras) reply(email.optJSONObject(Jmap.REPLY)) else null,
                    security = if (Jmap.SECURITY in extras) security(email.optJSONObject(Jmap.SECURITY)) else null
                )
            }
        }
        return out
    }

    /** The server's reading of one email: its own words and how it was delivered (either may be null). */
    data class Reading(val reply: Reply? = null, val security: Security? = null)

    companion object {
        private const val BATCH = 50

        /** The `Email` properties [emails] reads. */
        val PROPERTIES = listOf(
            "id", "threadId", "messageId", "inReplyTo", "references", "subject", "sentAt", "receivedAt",
            "from", "to", "cc", "replyTo", "keywords", "mailboxIds", "attachments"
        )

        /**
         * Maps one `Email/get` object to an [Entry]; [sent] are the Sent
         * mailbox ids. Null without an id. Malformed fields are left empty.
         */
        fun entry(email: JSONObject, sent: Set<String> = emptySet()): Entry? {
            val hex = email.optString("id").ifBlank { null } ?: return null
            val mailboxes = email.optJSONObject("mailboxIds")?.keySet()?.toSet().orEmpty()
            return Entry(
                hex = hex,
                messageId = strings(email.optJSONArray("messageId")).firstOrNull(),
                inReplyTo = strings(email.optJSONArray("inReplyTo")),
                references = strings(email.optJSONArray("references")),
                subject = text(email, "subject"),
                from = people(email.optJSONArray("from")).firstOrNull(),
                to = people(email.optJSONArray("to")),
                cc = people(email.optJSONArray("cc")),
                replyTo = people(email.optJSONArray("replyTo")),
                sentAt = instant(text(email, "sentAt")),
                receivedAt = instant(text(email, "receivedAt")),
                keywords = email.optJSONObject("keywords")?.let { k -> k.keySet().filter { k.optBoolean(it, true) }.toSet() }.orEmpty(),
                mailboxes = mailboxes,
                sent = mailboxes.isNotEmpty() && mailboxes.any { it in sent },
                attachments = try {
                    JmapContentTransport.attachments(email)
                } catch (e: Exception) {
                    Faults.report("thread attachments", e)
                    emptyList()
                },
                reply = reply(email.optJSONObject(Jmap.REPLY)),
                security = security(email.optJSONObject(Jmap.SECURITY))
            )
        }

        /** Maps an `aduki:reply` object; null when absent or without text. */
        fun reply(obj: JSONObject?): Reply? {
            if (obj == null || !obj.has("text") || obj.isNull("text")) return null
            val quote = obj.optJSONObject("quote")
            val forwarded = obj.optJSONObject("forwarded")?.let {
                Forwarded(from = nullable(it, "from"), date = nullable(it, "date"), subject = nullable(it, "subject"), text = text(it, "text"))
            }
            val text = text(obj, "text")
            return Reply(
                text = text,
                html = nullable(obj, "html"),
                quoteStart = quote?.let { q -> if (q.isNull("start")) null else q.optInt("start", -1).takeIf { it >= 0 } },
                quotedId = quote?.let { nullable(it, "from") }?.trim()?.removePrefix("<")?.removeSuffix(">"),
                author = quote?.let { nullable(it, "author") },
                signature = nullable(obj, "signature"),
                forwarded = forwarded,
                confidence = when (obj.optString("confidence").lowercase()) {
                    "high" -> Confidence.High
                    "medium" -> Confidence.Medium
                    else -> Confidence.Low
                },
                source = ReplySource.Server
            )
        }

        /** Maps an `aduki:security` object; null when absent. */
        fun security(obj: JSONObject?): Security? {
            if (obj == null) return null
            val transport = obj.optJSONObject("transport")?.let {
                Transport(tls = it.optBoolean("tls"), version = nullable(it, "version"), cipher = nullable(it, "cipher"), from = nullable(it, "from"))
            }
            val dkim = obj.optJSONArray("dkim")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map(::result) }
            return Security(
                transport = transport,
                spf = obj.optJSONObject("spf")?.let(::result),
                dkim = dkim,
                dmarc = obj.optJSONObject("dmarc")?.let(::result),
                signedBy = nullable(obj, "signedBy"),
                mailedBy = nullable(obj, "mailedBy"),
                internal = obj.optBoolean("internal")
            )
        }

        private fun result(obj: JSONObject) = AuthResult(
            result = text(obj, "result"),
            domain = nullable(obj, "domain"),
            selector = nullable(obj, "selector"),
            policy = nullable(obj, "policy")
        )

        private fun text(obj: JSONObject, name: String): String = if (obj.isNull(name)) "" else (obj.opt(name) as? String).orEmpty()

        private fun nullable(obj: JSONObject, name: String): String? = text(obj, name).ifEmpty { null }

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.filter { it.isNotBlank() }

        private fun people(arr: JSONArray?): List<Person> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                Person(name = nullable(it, "name"), email = text(it, "email"))
            }

        private fun instant(value: String): Instant? {
            if (value.isBlank()) return null
            return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: runCatching { Instant.parse(value) }.getOrNull()
        }
    }
}
