package pro.aduki.hermes.net.http

import org.json.JSONObject

/**
 * Envelope reads the Aduki REST envelope (ADK-NET-001 §3):
 * `{success: true, data}` or `{success: false, error: {status, kind, message}}`.
 *
 * Bodies that are not an envelope (servers from before it) pass through
 * unchanged, so the typed clients work against either.
 */
internal object Envelope {
    /** The `data` of a success envelope as JSON text, or [body] itself. */
    fun data(body: String): String {
        val obj = parse(body) ?: return body
        if (!obj.optBoolean("success", false)) return body
        val data = obj.opt("data")
        return if (data == null || data == JSONObject.NULL) "" else data.toString()
    }

    /** `"kind: message"` from a failure envelope, or null. */
    fun failure(body: String): String? {
        val error = parse(body)?.optJSONObject("error") ?: return null
        val kind = error.optString("kind")
        val message = error.optString("message")
        return when {
            kind.isBlank() && message.isBlank() -> null
            kind.isBlank() -> message
            message.isBlank() -> kind
            else -> "$kind: $message"
        }
    }

    private fun parse(body: String): JSONObject? {
        val text = body.trimStart()
        if (!text.startsWith("{")) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return if (obj.has("success")) obj else null
    }
}
