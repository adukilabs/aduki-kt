package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.calls.Meetings
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Call
import pro.aduki.core.models.CallDevice
import pro.aduki.core.models.CallEntry
import pro.aduki.core.models.CallLimits
import pro.aduki.core.models.CallPage
import pro.aduki.core.models.CallTicket
import pro.aduki.core.models.MeetingStatus
import pro.aduki.core.models.MissedCalls
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Where [Calls] gets its access tokens for audience `audio`: [token] with no
 * [stale] token gives a current one; with the token a request was refused
 * with (a `401`), a renewed one (unless another caller already renewed it).
 */
fun interface Bearer {
    fun token(stale: String?): String
}

/**
 * Calls is the client for the Aduki Calls API (`https://calls.aduki.pro/v1`,
 * audience `audio`): calls and meetings, ringing people, tickets into a call,
 * answers, the call log, devices that ring, relays, the plan and call quality.
 *
 * It does the HTTP side only. The media (the microphone, the speaker, the
 * call itself) is Aduki Calls' own SDK, which signs in through this client:
 * hand it [token] and [proof] as its `Identity`, so it never sees a refresh
 * token or a key.
 *
 * Aduki Calls accepts only DPoP-bound access tokens. [http] must carry the
 * [dpop] interceptor and no credentials of its own. A `401` renews the token
 * once through [bearer] and the request is sent once more.
 *
 * Ids are sixteen lowercase hexadecimal digits; where a meeting is meant,
 * its letter code (`abc-defg-hij`) or a whole meeting link works too
 * ([Meetings.code]). Calls are blocking: run them off the main thread (the
 * `Aduki.calls` facade does).
 */
class Calls(
    private val http: OkHttpClient,
    private val bearer: Bearer,
    private val dpop: Dpop?,
    private val base: String = Endpoints.CALLS
) {
    /** A client whose `audio` tokens come from [id]. */
    constructor(
        http: OkHttpClient,
        id: Id,
        dpop: Dpop,
        base: String = Endpoints.CALLS,
        audience: String = Endpoints.CALLS_AUDIENCE
    ) : this(http, Bearer { stale -> if (stale == null) id.token(audience) else id.rotate(audience, stale).token }, dpop, base)

    // The web origin the meeting links point at: the REST base without its `/v1`.
    private val origin = base.trimEnd('/').removeSuffix("/v1")

    // ---- the identity of Aduki Calls' SDK ---------------------------------------------------------------------------

    /** A current access token for audience `audio`, renewed when it runs out. */
    fun token(): String = bearer.token(null)

    /**
     * A DPoP proof for [method] on [url] bound to [token]; Aduki Calls' SDK
     * asks for one per request and for the signalling socket.
     */
    fun proof(method: String, url: String, token: String): String {
        val signer = dpop ?: throw IllegalStateException("Calls needs a DPoP key")
        return signer.proof(method, url, token, signer.nonce(url))
    }

    // ---- the service ------------------------------------------------------------------------------------------------

    /** What this server offers (`GET /v1/capabilities`): signalling URLs, push kinds, limits. */
    fun capabilities(): JSONObject = request("GET", "capabilities").obj()

    /**
     * Starts a call and rings [to] (account hexes, at most 20). The same
     * [idempotency] key again returns the same call.
     */
    fun start(to: List<String> = emptyList(), idempotency: String = UUID.randomUUID().toString()): Call {
        require(to.size <= TARGETS) { "at most $TARGETS people per call" }
        val body = JSONObject()
        if (to.isNotEmpty()) body.put("to", accounts(to))
        return call(request("POST", "calls", body = body, idempotency = idempotency).obj())
    }

    /**
     * Starts a meeting, open by its link until its host locks it: [title] at
     * most 120 characters, [lobby] its waiting room (`off`, `guests` or
     * `everyone`; the server's default is `off`). Share [Call.link].
     */
    fun meeting(
        title: String? = null,
        lobby: String? = null,
        idempotency: String = UUID.randomUUID().toString()
    ): Call {
        title?.let { require(it.codePointCount(0, it.length) <= TITLE) { "a title is at most $TITLE characters" } }
        lobby?.let { require(it in LOBBIES) { "lobby is off, guests or everyone" } }
        val body = JSONObject().put("kind", "meeting")
        if (title != null) body.put("title", title)
        if (lobby != null) body.put("lobby", lobby)
        return call(request("POST", "calls", body = body, idempotency = idempotency).obj())
    }

    /**
     * One call: its state and the invites of the people it rang (the owner
     * sees them all, the rung their own). [call] is its hex or a meeting code.
     */
    fun fetch(call: String): Call = call(request("GET", "calls/${Meetings.require(call, "call")}").obj())

    /**
     * The person's calls, newest first, within their history and without
     * the hidden ones; [limit] is 1 to 100. [missed] keeps the calls that
     * rang them and were missed, [kind] one kind, [with] the calls an
     * account was in too.
     */
    fun list(
        limit: Int? = null,
        after: String? = null,
        missed: Boolean = false,
        kind: String? = null,
        with: String? = null
    ): CallPage<Call> {
        limit?.let { require(it in 1..100) { "limit is 1 to 100" } }
        kind?.let { require(it in KINDS) { "kind is one of ${KINDS.joinToString()}" } }
        val query = mapOf(
            "limit" to limit,
            "after" to after?.let { hex(it) },
            "state" to if (missed) "missed" else null,
            "kind" to kind,
            "with" to with?.let { hex(it) }
        )
        return page(request("GET", "calls", query).obj(), ::call)
    }

    /** The calls ringing for this person now: what to show after a ring's push wakes the app. */
    fun ringing(): List<Call> = page(request("GET", "calls", mapOf("state" to "ringing")).obj(), ::call).items

    /** How many calls the person missed since they last looked ([seenMissed]). */
    fun missed(): MissedCalls = missedOf(request("GET", "calls/missed").obj())

    /** The person looked at their missed calls: the count starts again from zero on every device. */
    fun seenMissed(): MissedCalls = missedOf(request("PUT", "calls/missed/seen").obj())

    /** Hides [call] from the person's log (the others keep theirs). */
    fun hide(call: String) {
        request("DELETE", "calls/${hex(call)}/log")
    }

    /** Clears the person's whole call log (theirs only). */
    fun clearLog() {
        request("DELETE", "calls/log")
    }

    /** Rings more people (account hexes, 1 to 20) into a call the person is in or owns; a direct call becomes a group call. */
    fun invite(call: String, to: List<String>): Call {
        require(to.isNotEmpty()) { "ring at least one person" }
        require(to.size <= TARGETS) { "at most $TARGETS people at a time" }
        return call(request("POST", "calls/${hex(call)}/invites", body = JSONObject().put("to", accounts(to))).obj())
    }

    /**
     * The host's changes to a meeting or room: [locked] (only its parties
     * get in), a new [title], its [lobby] (`off` lets everyone waiting in),
     * or when someone the host muted may [unmute] (`self` or `host`).
     */
    fun change(
        call: String,
        locked: Boolean? = null,
        title: String? = null,
        lobby: String? = null,
        unmute: String? = null
    ): Call {
        require(locked != null || title != null || lobby != null || unmute != null) { "give locked, title, lobby or unmute" }
        title?.let { require(it.codePointCount(0, it.length) <= TITLE) { "a title is at most $TITLE characters" } }
        lobby?.let { require(it in LOBBIES) { "lobby is off, guests or everyone" } }
        unmute?.let { require(it == "self" || it == "host") { "unmute is self or host" } }
        val body = JSONObject()
        if (locked != null) body.put("locked", locked)
        if (title != null) body.put("title", title)
        if (lobby != null) body.put("lobby", lobby)
        if (unmute != null) body.put("unmute", unmute)
        return call(request("PATCH", "calls/${hex(call)}", body = body).obj())
    }

    /** Ends a meeting for everyone (its host or a workspace admin). It can be restarted with [reopen]. */
    fun end(call: String): Call = call(request("DELETE", "calls/${hex(call)}").obj())

    /**
     * Restarts a meeting or room the person made or hosts after it ended:
     * closed or expired, it is open again for a day ([Call.restarted]); only
     * emptied, nothing changes and joining restarts it. `403` for someone
     * who did not make it, `409` for another kind of call or a booked meeting.
     */
    fun reopen(call: String): Call = call(request("POST", "calls/${hex(call)}/reopen").obj())

    /**
     * Whether a meeting [code] names a meeting, whether anyone may join it
     * now, whether it ended, and its title when public. `429` past 30 a minute.
     */
    fun meetingStatus(code: String): MeetingStatus =
        status(request("GET", "meetings/${Meetings.require(code)}").obj())

    /**
     * A way into [call] (its hex or a meeting code): a ticket, or, when its
     * lobby holds the person, [CallEntry.Waiting] (the host is told; wait
     * for the socket's `admitted`, then ask again). `403` when the host
     * turned them away.
     */
    fun enter(call: String): CallEntry {
        val data = request("POST", "calls/${Meetings.require(call, "call")}/tokens").obj()
        if (data.optString("lobby") == "waiting") return CallEntry.Waiting(data.optString("call"), instant(data, "since"))
        return CallEntry.Ticket(ticket(data))
    }

    /**
     * A ticket into [call] (`ticket`, `expires` in seconds), presented once
     * on the signalling socket. While the lobby holds the person it fails
     * with [AdukiException.Api] `202 lobby.waiting`; use [enter] to wait.
     */
    fun ticket(call: String): CallTicket = when (val entry = enter(call)) {
        is CallEntry.Ticket -> entry.ticket
        is CallEntry.Waiting -> throw AdukiException.Api(202, "lobby.waiting", "The call's lobby holds this person")
    }

    /**
     * Answers a ring: accepted, a ticket into the call comes back; declined,
     * null. Once answered or missed (60 s), `409 state.conflict`.
     */
    fun answer(call: String, accept: Boolean): CallTicket? {
        val body = JSONObject().put("decision", if (accept) "accept" else "decline")
        val data = request("POST", "calls/${hex(call)}/answers", body = body).obj()
        return if (accept) ticket(data) else null
    }

    /** Leaves [call] on every device of this person. */
    fun leave(call: String) {
        request("DELETE", "calls/${hex(call)}/participants/me")
    }

    /** This person's Calls plan (`GET /v1/limits`). */
    fun limits(): CallLimits {
        val data = request("GET", "limits").obj()
        val limits = LinkedHashMap<String, Any?>()
        data.optJSONObject("limits")?.let { o ->
            for (key in o.keys()) limits[key] = when (val v = o.opt(key)) {
                JSONObject.NULL, null -> null
                is Number -> v.toLong()
                is Boolean -> v
                else -> v.toString()
            }
        }
        val used = LinkedHashMap<String, Long>()
        data.optJSONObject("usage")?.let { o -> for (key in o.keys()) used[key] = o.optLong(key) }
        val period = data.optJSONObject("period")
        return CallLimits(
            plan = data.optString("package"),
            limits = limits,
            used = used,
            enforced = strings(data.optJSONArray("enforced")),
            from = period?.let { instant(it, "from") },
            to = period?.let { instant(it, "to") }
        )
    }

    /**
     * Sends this person's final numbers for [call] when they leave it
     * (shares per mille, times in ms). Once per call session and only after
     * they were in it (else 404); a second is `409 state.conflict`.
     */
    fun report(call: String, report: JSONObject) {
        for (field in listOf("loss", "concealed")) {
            if (report.has(field)) require(report.optInt(field, -1) in 0..1000) { "$field is per mille: 0 to 1000" }
        }
        request("POST", "calls/${hex(call)}/reports", body = report)
    }

    /** The quality of [call] per person (`people`, each with a `mos` estimate) and the worst `mos`. */
    fun quality(call: String): JSONObject = request("GET", "calls/${hex(call)}/quality").obj()

    /** TURN servers for this person (`servers`, `ttl`, `expires`, `policy`); an empty list when the server has none. */
    fun relays(): JSONObject = request("POST", "relays").obj()

    // ---- devices that ring ------------------------------------------------------------------------------------------

    /**
     * Registers this device to ring for calls: [platform] `android`, `ios` or
     * `web`, with its FCM registration [token] (null: it rings nowhere, it
     * only lists its calls). The same token again returns the same device.
     */
    fun register(
        platform: String,
        token: String?,
        label: String? = null,
        idempotency: String = UUID.randomUUID().toString()
    ): CallDevice {
        val body = JSONObject()
            .put("platform", platform)
            .put("push", push(token))
            .put("capabilities", JSONArray(listOf("calls")))
        if (label != null) body.put("label", label)
        return device(request("POST", "devices", body = body, idempotency = idempotency).obj())
    }

    /** This person's devices. */
    fun devices(): List<CallDevice> = page(request("GET", "devices").obj(), ::device).items

    /** A new FCM [token] (after Firebase rotated it) or a new [label] for [device]. */
    fun edit(device: String, token: String? = null, label: String? = null): CallDevice {
        require(token != null || label != null) { "nothing to change" }
        val body = JSONObject()
        if (token != null) body.put("push", push(token))
        if (label != null) body.put("label", label)
        return device(request("PATCH", "devices/${hex(device)}", body = body).obj())
    }

    /** Stops [device] ringing (sign-out). */
    fun remove(device: String) {
        request("DELETE", "devices/${hex(device)}")
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private class Reply(val status: Int, val data: Any?) {
        fun obj(): JSONObject = data as? JSONObject ?: throw AdukiException.Protocol("Expected an object in the response")
    }

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
        fun build(token: String) = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply { idempotency?.let { header("Idempotency-Key", it) } }
            .method(method, payload ?: if (method == "GET" || method == "HEAD") null else EMPTY)
            .build()
        try {
            val sent = bearer.token(null)
            val first = http.newCall(build(sent)).execute()
            if (first.code != 401) return first.use(::reply)
            first.close()
            val fresh = bearer.token(sent)
            return http.newCall(build(fresh)).execute().use(::reply)
        } catch (e: IOException) {
            throw AdukiException.Network("Calls request failed: ${e.message}", e)
        }
    }

    private fun call(data: JSONObject): Call = callOf(data, origin)

    private fun ticket(data: JSONObject): CallTicket {
        val ticket = data.optString("ticket").ifBlank { throw AdukiException.Protocol("A ticket answer without a ticket") }
        return CallTicket(ticket, data.optLong("expires"), data.optString("call"))
    }

    private fun device(data: JSONObject) = CallDevice(
        id = data.optString("id"),
        platform = data.optString("platform"),
        push = data.optJSONObject("push")?.optString("kind").orEmpty(),
        label = text(data, "label"),
        state = data.optString("state")
    )

    private fun missedOf(data: JSONObject) = MissedCalls(data.optInt("count"), instant(data, "seen"))

    private fun <T> page(data: JSONObject, item: (JSONObject) -> T): CallPage<T> {
        val items = data.optJSONArray("items") ?: throw AdukiException.Protocol("List without items")
        return CallPage((0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(item) }, text(data, "next"))
    }

    private fun accounts(to: List<String>) = JSONArray(to.map { JSONObject().put("account", hex(it)) })

    private fun push(token: String?): JSONObject =
        if (token == null) JSONObject().put("kind", "none") else JSONObject().put("kind", "fcm").put("token", token)

    private fun hex(value: String): String {
        require(HEX.matches(value)) { "An id is sixteen lowercase hexadecimal digits" }
        return value
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody()
        private val HEX = Regex("^[0-9a-f]{16}$")
        private val LOBBIES = setOf("off", "guests", "everyone")
        private val KINDS = setOf("direct", "group", "meeting", "room", "thread", "center")
        private const val TARGETS = 20
        private const val TITLE = 120

        /**
         * [meetingStatus] without an account (a join screen before sign-in):
         * the same answer, counted per network only. [http] needs no
         * credentials.
         */
        fun status(http: OkHttpClient, code: String, base: String = Endpoints.CALLS): MeetingStatus {
            val url = "${base.trimEnd('/')}/meetings/${Meetings.require(code)}"
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            val reply = try {
                http.newCall(request).execute().use(::reply)
            } catch (e: IOException) {
                throw AdukiException.Network("Calls request failed: ${e.message}", e)
            }
            return status(reply.obj())
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
            if (response.code == 401) throw AdukiException.Unauthorized("$kind: $message", null, kind)
            throw AdukiException.Api(
                status = response.code,
                kind = kind,
                message = message,
                details = error?.optJSONObject("details")?.toString(),
                retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            )
        }

        private fun status(data: JSONObject): MeetingStatus {
            val exists = data.optBoolean("exists", false)
            return MeetingStatus(
                exists = exists,
                open = data.optBoolean("open", false),
                ended = data.optBoolean("ended", false),
                organiser = data.optBoolean("organiser", false),
                title = text(data, "title"),
                call = text(data, "call"),
                code = if (exists) text(data, "code") else null
            )
        }

        /** A [Call] from its JSON; [origin] is the Calls web origin its link points at. */
        fun callOf(data: JSONObject, origin: String = Meetings.ORIGIN): Call {
            val id = data.optString("call").ifBlank { throw AdukiException.Protocol("A call without its id") }
            val join = data.optJSONObject("join")
            val code = text(data, "code")
            val ringing = data.optJSONObject("ringing")
            val invites = data.optJSONArray("invites")
            return Call(
                call = id,
                kind = data.optString("kind"),
                state = data.optString("state"),
                participants = data.optInt("participants"),
                owner = data.optString("owner"),
                ownerName = text(data, "ownerName"),
                ownerHandle = text(data, "ownerHandle"),
                created = instant(data, "created"),
                expires = instant(data, "expires"),
                signal = join?.optString("signal").orEmpty(),
                room = join?.optString("room").orEmpty().ifBlank { id },
                code = code,
                link = join?.let { text(it, "link") } ?: code?.let { "${origin.trimEnd('/')}/call/$it" },
                title = text(data, "title"),
                host = text(data, "host"),
                closed = instant(data, "closed"),
                locked = instant(data, "locked"),
                lobby = text(data, "lobby"),
                restarted = data.optBoolean("restarted", false),
                ringing = ringing?.let { Call.Ringing(it.optInt("people"), it.optInt("devices")) },
                invites = if (invites == null) emptyList() else (0 until invites.length()).mapNotNull { invites.optJSONObject(it)?.let(::invite) },
                invite = data.optJSONObject("invite")?.let(::invite),
                thread = text(data, "thread")
            )
        }

        private fun invite(o: JSONObject) = Call.Invite(
            account = o.optString("account"),
            state = o.optString("state"),
            since = instant(o, "since"),
            name = text(o, "name"),
            handle = text(o, "handle")
        )

        private fun text(o: JSONObject, key: String): String? =
            if (!o.has(key) || o.isNull(key)) null else o.optString(key).ifBlank { null }

        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.optString(it) }

        private fun instant(o: JSONObject, key: String): Instant? {
            val raw = text(o, key) ?: return null
            return runCatching { Instant.parse(raw) }.recoverCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        }
    }
}
