package pro.aduki.core.calendar

import java.nio.charset.Charset
import java.util.Base64

/**
 * Invitation is the iCalendar object an iMIP email carries (RFC 6047) with
 * its iTIP [method] (`REQUEST`, `CANCEL`, `REPLY`, ...), upper case.
 */
data class Invitation(val method: String, val ical: String)

/**
 * Imip reads the calendar part of an email (RFC 6047): the invitation, its
 * update or cancellation an organizer sends, or an attendee's reply. It
 * takes the raw RFC 5322 source (`Mail.source`).
 */
object Imip {

    private class Part(val headers: Map<String, String>, val body: String)

    /**
     * The first `text/calendar` part of [raw] (or, without one, an
     * `application/ics` attachment), decoded, with its method: the
     * object's `METHOD`, else the `method` parameter of its Content-Type.
     * Null when the message has no calendar part.
     */
    fun read(raw: String): Invitation? {
        val parts = mutableListOf<Part>()
        collect(part(raw), parts, depth = 0)
        val chosen = parts.firstOrNull { type(it) == "text/calendar" }
            ?: parts.firstOrNull { type(it) == "application/ics" }
            ?: return null
        val text = decode(chosen)
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return null
        val method = Events.calendar(text)?.first("METHOD")?.value?.trim()?.ifEmpty { null }
            ?: param(chosen.headers["content-type"].orEmpty(), "method").orEmpty()
        return Invitation(method.uppercase(), text)
    }

    // Splits a message or body part into its headers (names lower case, folds undone) and body.
    private fun part(text: String): Part {
        val normal = text.replace("\r\n", "\n")
        val end = when {
            normal.startsWith("\n") -> 0
            else -> normal.indexOf("\n\n").let { if (it < 0) normal.length else it + 1 }
        }
        val headers = LinkedHashMap<String, String>()
        var name: String? = null
        for (line in normal.substring(0, end).lines()) {
            if (line.isEmpty()) continue
            if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) {
                headers[name] = headers[name] + " " + line.trim()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            name = line.substring(0, colon).trim().lowercase()
            if (!headers.containsKey(name)) headers[name] = line.substring(colon + 1).trim() else name = null
        }
        val body = if (end >= normal.length) "" else normal.substring(end).removePrefix("\n")
        return Part(headers, body)
    }

    // The leaf parts of [part], in order; multiparts are opened (a few levels deep).
    private fun collect(part: Part, out: MutableList<Part>, depth: Int) {
        val contentType = part.headers["content-type"].orEmpty()
        val boundary = param(contentType, "boundary")
        if (!type(part).startsWith("multipart/") || boundary.isNullOrEmpty() || depth > 5) {
            out += part
            return
        }
        val delimiter = "--$boundary"
        val chunks = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        for (line in part.body.lines()) {
            val trimmed = line.trimEnd()
            if (trimmed == "$delimiter--") break
            if (trimmed == delimiter) {
                current = StringBuilder().also { chunks += it }
                continue
            }
            current?.append(line)?.append('\n')
        }
        for (chunk in chunks) collect(part(chunk.toString()), out, depth + 1)
    }

    private fun type(part: Part): String =
        part.headers["content-type"].orEmpty().substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }

    // A parameter of a header value (`text/calendar; method=REQUEST; charset="utf-8"`), unquoted.
    private fun param(value: String, name: String): String? =
        value.split(';').drop(1).map { it.trim() }.firstOrNull { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"')

    private fun decode(part: Part): String {
        val charset = param(part.headers["content-type"].orEmpty(), "charset")
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val bytes = when (part.headers["content-transfer-encoding"]?.trim()?.lowercase()) {
            "base64" -> runCatching { Base64.getMimeDecoder().decode(part.body.filterNot { it.isWhitespace() }) }
                .getOrDefault(ByteArray(0))
            "quoted-printable" -> quoted(part.body)
            else -> return part.body
        }
        return String(bytes, charset)
    }

    // Quoted-printable (RFC 2045 §6.7): soft line breaks dropped, `=XX` to its byte.
    private fun quoted(text: String): ByteArray {
        val soft = text.replace("=\r\n", "").replace("=\n", "")
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < soft.length) {
            val c = soft[i]
            val byte = if (c == '=' && i + 2 < soft.length) soft.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (c == '=' && byte != null) {
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return out.toByteArray()
    }
}
