package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Body
import pro.aduki.core.models.BodyMode
import pro.aduki.net.http.Jmap
import pro.aduki.sync.engine.ContentTransport

/**
 * JmapContentTransport reads a message body with JMAP `Email/get`.
 *
 * When the session advertises [Jmap.READING], the server reads the body: it
 * sends `aduki:bodyMode` and returns sanitised HTML and unwrapped text
 * ([Body.sanitised] true). Otherwise it fetches the text and HTML parts with
 * their values, raw ([Body.sanitised] false), whatever the mode.
 */
class JmapContentTransport(private val jmap: Jmap) : ContentTransport {

    override suspend fun fetch(hex: String): Body = fetch(hex, BodyMode.Both)

    override suspend fun fetch(hex: String, mode: BodyMode): Body = withContext(Dispatchers.IO) {
        val reading = Jmap.READING in jmap.session().capabilities
        val args = JSONObject().put("ids", JSONArray().put(hex))
        val email = if (reading) {
            args.put("properties", JSONArray(listOf("id", "attachments")))
                .put(MODE, wire(mode))
            first(hex, jmap.call("Email/get", args, Jmap.USING + Jmap.READING))
        } else {
            args.put("properties", JSONArray(listOf("id", "bodyValues", "textBody", "htmlBody", "attachments")))
                .put("fetchTextBodyValues", true)
                .put("fetchHTMLBodyValues", true)
            first(hex, jmap.call("Email/get", args))
        }
        if (reading) read(hex, email) else body(hex, email)
    }

    override suspend fun sanitised(): Boolean = withContext(Dispatchers.IO) {
        Jmap.READING in jmap.session().capabilities
    }

    private fun first(hex: String, reply: JSONObject): JSONObject =
        reply.optJSONArray("list")?.optJSONObject(0)
            ?: throw AdukiException.Api(404, "entity.missing", "message $hex not found")

    companion object {
        /** The `Email/get` argument naming the [BodyMode]. */
        const val MODE = "aduki:bodyMode"

        /** The wire name of [mode]. */
        fun wire(mode: BodyMode): String = when (mode) {
            BodyMode.Rich -> "rich"
            BodyMode.Plain -> "plain"
            BodyMode.Both -> "both"
        }

        /**
         * Maps one `Email/get` object read with `aduki:bodyMode` to a
         * sanitised [Body]. A part the mode leaves out, or that the server
         * could not read (null), is empty.
         */
        fun read(hex: String, email: JSONObject): Body {
            fun string(name: String): String = if (email.isNull(name)) "" else email.optString(name)
            return Body(
                hex = hex,
                text = string("aduki:text"),
                html = string("aduki:html"),
                attachments = attachments(email),
                quoteStart = if (email.isNull("aduki:quoteStart")) null else email.optInt("aduki:quoteStart"),
                hasText = email.optBoolean("aduki:hasText"),
                hasHtml = email.optBoolean("aduki:hasHtml"),
                sanitised = true
            )
        }

        /** Maps one plain `Email/get` object (parts and their values) to a raw [Body]. */
        fun body(hex: String, email: JSONObject): Body {
            val values = email.optJSONObject("bodyValues") ?: JSONObject()
            fun join(parts: JSONArray?, type: String): String {
                if (parts == null) return ""
                return (0 until parts.length())
                    .map { parts.getJSONObject(it) }
                    .filter { it.optString("type").equals(type, ignoreCase = true) }
                    .mapNotNull { values.optJSONObject(it.optString("partId"))?.optString("value") }
                    .joinToString("\n")
            }
            return Body(
                hex = hex,
                text = join(email.optJSONArray("textBody"), "text/plain"),
                html = join(email.optJSONArray("htmlBody"), "text/html"),
                attachments = attachments(email)
            )
        }

        private fun attachments(email: JSONObject): List<Attachment> {
            val list = email.optJSONArray("attachments") ?: JSONArray()
            return (0 until list.length()).map { i ->
                val part = list.getJSONObject(i)
                Attachment(
                    blob = part.optString("blobId"),
                    name = if (part.isNull("name")) "" else part.optString("name"),
                    type = part.optString("type"),
                    size = part.optLong("size"),
                    cid = if (part.isNull("cid")) null else part.optString("cid").ifBlank { null },
                    inline = part.optString("disposition").equals("inline", ignoreCase = true)
                )
            }
        }
    }
}
