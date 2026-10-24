package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Body
import pro.aduki.net.http.Jmap
import pro.aduki.sync.engine.ContentTransport

/**
 * JmapContentTransport reads a message body with JMAP `Email/get`: the text
 * and HTML parts with their values, and the attachment list.
 */
class JmapContentTransport(private val jmap: Jmap) : ContentTransport {

    override suspend fun fetch(hex: String): Body = withContext(Dispatchers.IO) {
        val args = JSONObject()
            .put("ids", JSONArray().put(hex))
            .put("properties", JSONArray(listOf("id", "bodyValues", "textBody", "htmlBody", "attachments")))
            .put("fetchTextBodyValues", true)
            .put("fetchHTMLBodyValues", true)
        val reply = jmap.call("Email/get", args)
        val email = reply.optJSONArray("list")?.optJSONObject(0)
            ?: throw AdukiException.Api(404, "entity.missing", "message $hex not found")
        body(hex, email)
    }

    companion object {
        /** Maps one `Email/get` object to a [Body]. */
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
            val list = email.optJSONArray("attachments") ?: JSONArray()
            val attachments = (0 until list.length()).map { i ->
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
            return Body(
                hex = hex,
                text = join(email.optJSONArray("textBody"), "text/plain"),
                html = join(email.optJSONArray("htmlBody"), "text/html"),
                attachments = attachments
            )
        }
    }
}
