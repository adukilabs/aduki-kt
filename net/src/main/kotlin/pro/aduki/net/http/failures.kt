package pro.aduki.net.http

import okhttp3.Response
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException

/**
 * Failures turns an Aduki ID error answer into a typed [AdukiException] so a
 * client drops its tokens only when the session is over (ADK-AUTH-001 §10):
 *
 * - `401 auth.dpop`, `401 auth.nonce` and OAuth `400 use_dpop_nonce` are
 *   [AdukiException.Proof]: nothing changed at the server, keep the tokens.
 * - any other `401` is [AdukiException.Unauthorized] with its `kind`;
 *   [AdukiException.Unauthorized.ended] tells whether the session is over.
 * - anything else is [AdukiException.Network] with the status code (the
 *   convention of the Aduki ID clients since 0.4), its message carrying
 *   `kind: message`.
 */
internal object Failures {

    /** The kinds that mean "retry with the server nonce". */
    val NONCE = setOf("auth.nonce", "use_dpop_nonce")

    /** The kinds of a refused DPoP proof. */
    val PROOF = NONCE + "auth.dpop"

    /** The error `kind` (or OAuth `error`) of a failure body, or blank. */
    fun kind(body: String): String {
        val json = runCatching { JSONObject(body.trimStart()) }.getOrNull() ?: return ""
        json.optJSONObject("error")?.optString("kind")?.takeIf { it.isNotBlank() }?.let { return it }
        // OAuth endpoints answer `{"error": "use_dpop_nonce", ...}` (no envelope).
        if (json.has("success")) return ""
        return json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }.orEmpty()
    }

    /** The nonce of a failure: the `DPoP-Nonce` header, else `error.details.nonce`. */
    fun nonce(response: Response, body: String): String? =
        response.header("DPoP-Nonce")?.ifBlank { null }
            ?: runCatching {
                JSONObject(body).optJSONObject("error")?.optJSONObject("details")?.optString("nonce")
            }.getOrNull()?.ifBlank { null }

    /** Throws the exception for the failed [response] whose body was [body]. */
    fun raise(response: Response, body: String, what: String): Nothing {
        val json = runCatching { JSONObject(body.trimStart()) }.getOrNull()
        val error = json?.optJSONObject("error")
        val kind = kind(body)
        val message = (error?.optString("message") ?: json?.optString("error_description"))
            .orEmpty().ifBlank { "HTTP ${response.code}" }
        val text = if (kind.isBlank()) message else "$kind: $message"
        if (kind in PROOF && (response.code == 401 || response.code == 400)) {
            throw AdukiException.Proof("$what: $text", kind, nonce(response, body))
        }
        if (response.code == 401) throw AdukiException.Unauthorized(text, null, kind)
        throw AdukiException.Network("$what failed: $text", code = response.code, retryAfter = Envelope.retryAfter(response))
    }
}
