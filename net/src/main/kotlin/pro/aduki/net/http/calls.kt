package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import java.io.IOException
import java.util.UUID

/**
 * Calls is the client for the aduki Calls API (ADK-AUDIO-013): calls, ringing
 * people, tickets into a call, answers, devices that ring, relays, the plan
 * (limits and use) and call quality (final reports, quality per person).
 *
 * It does the HTTP side only. The media (the microphone, the speaker, the
 * call itself) is aduki Calls' own SDK, which signs in through this client:
 * hand it [token] and [proof] as its `Identity`, so it never sees a refresh
 * token or a key.
 *
 * aduki Calls accepts only DPoP-bound access tokens (audience `audio`). Build
 * the [Id] and this client's HTTP client with the same [dpop] interceptor
 * (see `Aduki.builder().dpop(key)`). A 401 renews the token once through
 * [Id.call].
 *
 * Bodies and results are [JSONObject]s of the documented fields; the server
 * refuses unknown fields in requests. Ids are sixteen lowercase hexadecimal
 * digits. aduki ID does not issue `audio` tokens yet: until it does, every
 * call here ends in `401`.
 */
class Calls(
    private val http: okhttp3.OkHttpClient,
    private val id: Id,
    private val dpop: Dpop,
    private val base: String = Endpoints.CALLS,
    private val audience: String = Endpoints.CALLS_AUDIENCE
) {
    /** A successful answer: the `data` of the envelope and the HTTP status. */
    class Reply(val status: Int, val data: Any?) {
        /** `data` as an object. */
        fun obj(): JSONObject = data as? JSONObject ?: throw AdukiException.Protocol("Expected an object in the response")
    }

    /** One page of a list: `items` and the cursor of the next page, null at the end. */
    class Page(val items: List<JSONObject>, val next: String?)

    // ---- the identity of aduki Calls' SDK ---------------------------------------------------------------------------

    /** A current access token for audience `audio`, renewed when it runs out. */
    fun token(): String = id.token(audience)

    /**
     * A DPoP proof for [method] on [url] bound to [token]; aduki Calls' SDK asks
     * for one per request and for the signalling socket (`GET wss://.../v1/signal`).
     */
    fun proof(method: String, url: String, token: String): String = dpop.proof(method, url, token, dpop.nonce(url))

    // ---- the service ------------------------------------------------------------------------------------------------

    /** What this server offers (`GET /v1/capabilities`): signalling URLs, push kinds, limits. */
    fun capabilities(): JSONObject = request("GET", "capabilities").obj()

    /**
     * Starts a call and rings [to] (account hexes, at most 20), whose devices
     * get a ring. The same [idempotency] key again returns the same call. The
     * answer carries `call`, `join.signal` and, when people were rung,
     * `ringing.{people, devices}`.
     */
    fun start(to: List<String> = emptyList(), idempotency: String = UUID.randomUUID().toString()): JSONObject {
        require(to.size <= TARGETS) { "at most $TARGETS people per call" }
        val body = JSONObject()
        if (to.isNotEmpty()) body.put("to", JSONArray(to.map { JSONObject().put("account", hex(it)) }))
        return request("POST", "calls", body = body, idempotency = idempotency).obj()
    }

    /** One call: its state, and the invites of the people it rang (the owner sees them all, the rung their own). */
    fun fetch(call: String): JSONObject = request("GET", "calls/${hex(call)}").obj()

    /** The caller's calls, newest first; [limit] is 1 to 100. */
    fun list(limit: Int? = null, after: String? = null): Page {
        limit?.let { require(it in 1..100) { "limit is 1 to 100" } }
        return page(request("GET", "calls", mapOf("limit" to limit, "after" to after?.let(::hex))).obj())
    }

    /** The calls ringing for this person now: what to show after a ring's push wakes the app. */
    fun ringing(): List<JSONObject> = page(request("GET", "calls", mapOf("state" to "ringing")).obj()).items

    /** A ticket into [call] (`ticket`, `expires` in seconds), presented once on the signalling socket. */
    fun ticket(call: String): JSONObject = request("POST", "calls/${hex(call)}/tokens").obj()

    /**
     * Answers a ring: accepted, a ticket into the call comes back; declined,
     * the caller sees it. Once answered or missed (60 s), `409 state.conflict`.
     */
    fun answer(call: String, accept: Boolean): JSONObject {
        val body = JSONObject().put("decision", if (accept) "accept" else "decline")
        return request("POST", "calls/${hex(call)}/answers", body = body).obj()
    }

    /** Leaves [call] on every device of this person. */
    fun leave(call: String) {
        request("DELETE", "calls/${hex(call)}/participants/me")
    }

    /**
     * This person's plan (`GET /v1/limits`): `package`, `limits` (the
     * `audio.*` keys, null for unlimited), `usage` (`audio.calls` at a time,
     * `audio.minutes` this UTC month), `period` and `enforced`. A new call
     * past them is `403 plan.limit`.
     */
    fun limits(): JSONObject = request("GET", "limits").obj()

    /**
     * Sends this person's final numbers for [call] when they leave it
     * (`agent`, `filter`, `bitrate`, `loss`, `rtt`, `jitter`, `concealed`,
     * `received`, `lost`, `level`, `agc`, `restarts`, `input`, `sent`,
     * `heard`; shares per mille, times in ms). Once per call session and only
     * after they were in it (else 404); a second is `409 state.conflict`.
     */
    fun report(call: String, report: JSONObject) {
        for (field in listOf("loss", "concealed")) {
            if (report.has(field)) require(report.optInt(field, -1) in 0..1000) { "$field is per mille: 0 to 1000" }
        }
        request("POST", "calls/${hex(call)}/reports", body = report)
    }

    /** The quality of [call] per person (`people`, each with a `mos` estimate) and the worst `mos`; for its owner and participants. */
    fun quality(call: String): JSONObject = request("GET", "calls/${hex(call)}/quality").obj()

    /** TURN servers for this person (`servers`, `ttl`, `expires`, `policy`); an empty list when the server has none. */
    fun relays(): JSONObject = request("POST", "relays").obj()

    // ---- devices that ring ------------------------------------------------------------------------------------------

    /**
     * Registers this device to ring for calls: [platform] `android`, `ios` or
     * `web`, with its FCM registration [token] (null: the device rings
     * nowhere, it only lists its calls). Registering the same token again
     * returns the same device. The answer's `id` is the device.
     */
    fun register(
        platform: String,
        token: String?,
        label: String? = null,
        idempotency: String = UUID.randomUUID().toString()
    ): JSONObject {
        val body = JSONObject()
            .put("platform", platform)
            .put("push", push(token))
            .put("capabilities", JSONArray(listOf("calls")))
        if (label != null) body.put("label", label)
        return request("POST", "devices", body = body, idempotency = idempotency).obj()
    }

    /** This person's devices. */
    fun devices(): List<JSONObject> = page(request("GET", "devices").obj()).items

    /** A new FCM [token] (after Firebase rotated it) or a new [label] for [device]. */
    fun edit(device: String, token: String? = null, label: String? = null): JSONObject {
        require(token != null || label != null) { "nothing to change" }
        val body = JSONObject()
        if (token != null) body.put("push", push(token))
        if (label != null) body.put("label", label)
        return request("PATCH", "devices/${hex(device)}", body = body).obj()
    }

    /** Stops [device] ringing (sign-out). */
    fun remove(device: String) {
        request("DELETE", "devices/${hex(device)}")
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private fun request(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JSONObject? = null,
        idempotency: String? = null
    ): Reply {
        idempotency?.let { require(runCatching { UUID.fromString(it) }.isSuccess) { "Idempotency-Key must be a UUID" } }
        val url = "${base.trimEnd('/')}/${path.trimStart('/')}".toHttpUrl().newBuilder().apply {
            for ((k, v) in query) if (v != null) addQueryParameter(k, v.toString())
        }.build()
        val payload = body?.toString()?.toRequestBody(JSON)
        val response = try {
            id.call(audience) { token ->
                Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
                    .apply { idempotency?.let { header("Idempotency-Key", it) } }
                    .method(method, payload ?: if (method == "GET" || method == "HEAD") null else EMPTY)
                    .build()
            }
        } catch (e: IOException) {
            throw AdukiException.Network("Calls request failed: ${e.message}", e)
        }
        response.use { return reply(it) }
    }

    private fun reply(response: Response): Reply {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (response.isSuccessful) {
            if (response.code == 204) return Reply(204, null)
            if (json == null || !json.optBoolean("success", false)) throw AdukiException.Protocol("Response is not a success envelope")
            return Reply(response.code, json.opt("data").takeUnless { it == JSONObject.NULL })
        }
        val error = json?.optJSONObject("error")
        val kind = error?.optString("kind").orEmpty().ifBlank { "http.${response.code}" }
        val message = error?.optString("message").orEmpty().ifBlank { "HTTP ${response.code}" }
        if (response.code == 401) throw AdukiException.Unauthorized("$kind: $message")
        throw AdukiException.Api(
            status = response.code,
            kind = kind,
            message = message,
            details = error?.optJSONObject("details")?.toString(),
            retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        )
    }

    private fun page(data: JSONObject): Page {
        val items = data.optJSONArray("items") ?: throw AdukiException.Protocol("List without items")
        return Page((0 until items.length()).map { items.getJSONObject(it) }, data.optString("next").ifBlank { null })
    }

    private fun push(token: String?): JSONObject =
        if (token == null) JSONObject().put("kind", "none") else JSONObject().put("kind", "fcm").put("token", token)

    private fun hex(value: String): String {
        require(HEX.matches(value)) { "An id is sixteen lowercase hexadecimal digits" }
        return value
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody()
        val HEX = Regex("^[0-9a-f]{16}$")
        const val TARGETS = 20
    }
}
