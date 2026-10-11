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
 * Space is the client for the Aduki Space REST API (ADK-SPACE-007, `space`
 * repo `guide/api/api.md`): the transport rules that are final (envelope,
 * keyset lists, `Idempotency-Key`, `ETag` / `If-Match`, error kinds) and thin
 * helpers for the work, support, search and settings routes whose request and
 * response shapes are read from the Space handlers.
 *
 * Space accepts only DPoP-bound access tokens (audience `space`). Build the
 * [Id] and this client's HTTP client with the same [Dpop] interceptor (see
 * `Aduki.builder().dpop(key)`), so the token is bound at sign-in and each call
 * carries `Authorization: DPoP` with a proof. A 401 renews the token once
 * through [Id.call].
 *
 * Bodies and results are [JSONObject]s of the documented fields: the DTOs of
 * the Space guide are still Draft, and unknown fields are ignored by clients
 * (adding fields is not breaking) but **refused by the server** in requests,
 * so only send documented fields. Money is integer minor units with a
 * `currency` sibling. Never run against a real Space server yet.
 */
class Space(
    private val http: okhttp3.OkHttpClient,
    private val id: Id,
    private val base: String = Endpoints.SPACE,
    private val audience: String = Endpoints.SPACE_AUDIENCE
) {
    /** A successful answer: the `data` of the envelope, the HTTP status and the `ETag` (without quotes). */
    class Reply(val status: Int, val data: Any?, val etag: String?) {
        /** `data` as an object. */
        fun obj(): JSONObject = data as? JSONObject ?: throw AdukiException.Protocol("Expected an object in the response")
    }

    /** One page of a list: `items` and the cursor of the next page, null at the end. */
    class Page(val items: List<JSONObject>, val next: String?)

    /** One request. [query] is encoded in order; null values are dropped; [idempotency] and [ifMatch] set the headers. */
    fun call(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JSONObject? = null,
        idempotency: String? = null,
        ifMatch: String? = null
    ): Reply {
        idempotency?.let { require(runCatching { UUID.fromString(it) }.isSuccess) { "Idempotency-Key must be a UUID" } }
        val url = "${base.trimEnd('/')}/${path.trimStart('/')}".toHttpUrl().newBuilder().apply {
            for ((k, v) in query) if (v != null) addQueryParameter(k, v.toString())
        }.build()
        val payload = body?.toString()?.toRequestBody(JSON)
        val response = try {
            id.call(audience) { token ->
                Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
                    .apply {
                        idempotency?.let { header("Idempotency-Key", it) }
                        ifMatch?.let { header("If-Match", if (it == "*" || it.startsWith("\"")) it else "\"$it\"") }
                    }
                    .method(method, payload ?: if (method == "GET" || method == "HEAD") null else EMPTY)
                    .build()
            }
        } catch (e: IOException) {
            throw AdukiException.Network("Space request failed: ${e.message}", e)
        }
        response.use { return reply(it) }
    }

    /** `GET` a list route: `limit` is 1 to 100, `after` the cursor of the previous page. */
    fun list(path: String, query: Map<String, Any?> = emptyMap(), limit: Int? = null, after: String? = null): Page {
        limit?.let { require(it in 1..100) { "limit is 1 to 100" } }
        val data = call("GET", path, query + mapOf("limit" to limit, "after" to after)).obj()
        val items = data.optJSONArray("items") ?: throw AdukiException.Protocol("List without items")
        return Page((0 until items.length()).map { items.getJSONObject(it) }, data.optString("next").ifBlank { null })
    }

    /** Every item of a list route, fetching pages of [size] as the sequence is consumed. */
    fun all(path: String, query: Map<String, Any?> = emptyMap(), size: Int = 100): Sequence<JSONObject> = sequence {
        var after: String? = null
        do {
            val page = list(path, query, size, after)
            yieldAll(page.items)
            after = page.next
        } while (after != null)
    }

    // ---- tasks and check-ins (work/tasks.md) ------------------------------------------------------------------------

    /** Tasks; filters are those of `GET /v1/tasks` (`status`, `client`, `team`, `mine`, `orphaned`, `unbilled`, `q`, `archived`). */
    fun tasks(
        status: String? = null, client: String? = null, team: String? = null, mine: Boolean = false,
        orphaned: Boolean = false, unbilled: Boolean = false, q: String? = null, archived: Boolean = false,
        limit: Int? = null, after: String? = null
    ): Page {
        client?.let(::hex); team?.let(::hex)
        return list(
            "tasks",
            mapOf(
                "status" to status, "client" to client, "team" to team, "mine" to mine.takeIf { it },
                "orphaned" to orphaned.takeIf { it }, "unbilled" to unbilled.takeIf { it }, "q" to q?.takeIf { it.isNotEmpty() },
                "archived" to archived.takeIf { it }
            ),
            limit, after
        )
    }

    /** A task and its version (`ETag`), for [editTask]. */
    fun task(task: String): Reply = call("GET", "tasks/${hex(task)}")

    /** Creates a task (`title` required); a new key is generated unless [idempotency] is given. */
    fun addTask(fields: JSONObject, idempotency: String = UUID.randomUUID().toString()): Reply =
        call("POST", "tasks", body = fields, idempotency = idempotency)

    /** Edits a task; [version] is the `ETag` of the read the edit is based on (stale is `409 entity.conflict`). */
    fun editTask(task: String, changes: JSONObject, version: String? = null): Reply =
        call("PATCH", "tasks/${hex(task)}", body = changes, ifMatch = version)

    /** Check-ins of a task. */
    fun checkins(task: String, limit: Int? = null, after: String? = null): Page = list("tasks/${hex(task)}/checkins", emptyMap(), limit, after)

    /** Submits a check-in: `minutes` of work (never hours) and an optional `proof` link. */
    fun addCheckin(task: String, body: String, minutes: Int = 0, proof: String? = null, idempotency: String = UUID.randomUUID().toString()): Reply {
        require(minutes >= 0) { "minutes must not be negative" }
        val json = JSONObject().put("body", body).put("minutes", minutes)
        if (proof != null) json.put("proof", proof)
        return call("POST", "tasks/${hex(task)}/checkins", body = json, idempotency = idempotency)
    }

    // ---- tickets (work/tickets.md) ----------------------------------------------------------------------------------

    /** Tickets; filters are those of `GET /v1/tickets`. */
    fun tickets(
        status: String? = null, client: String? = null, assignee: String? = null, unassigned: Boolean = false,
        mine: Boolean = false, breached: Boolean = false, q: String? = null, limit: Int? = null, after: String? = null
    ): Page {
        client?.let(::hex); assignee?.let(::hex)
        return list(
            "tickets",
            mapOf(
                "status" to status, "client" to client, "assignee" to assignee, "unassigned" to unassigned.takeIf { it },
                "mine" to mine.takeIf { it }, "breached" to breached.takeIf { it }, "q" to q?.takeIf { it.isNotEmpty() }
            ),
            limit, after
        )
    }

    /** Opens a ticket (`client` and `title` required). */
    fun addTicket(fields: JSONObject, idempotency: String = UUID.randomUUID().toString()): Reply =
        call("POST", "tickets", body = fields, idempotency = idempotency)

    /** The thread of a ticket, oldest first; message ids are integers and the paging key. */
    fun messages(ticket: String, limit: Int? = null, after: String? = null): Page = list("tickets/${hex(ticket)}/messages", emptyMap(), limit, after)

    /** Replies on a ticket; [internal] makes it a staff note the client never sees. */
    fun reply(ticket: String, body: String, internal: Boolean = false, idempotency: String = UUID.randomUUID().toString()): Reply =
        call("POST", "tickets/${hex(ticket)}/messages", body = JSONObject().put("body", body).put("internal", internal), idempotency = idempotency)

    // ---- search and settings ----------------------------------------------------------------------------------------

    /**
     * Ids by query (`GET /v1/search`): `q` is 1 to 200 characters, [limit] 1 to
     * 50 (default 20 at the server), [entity] narrows to one collection. The
     * result lists `{collection, id}` pairs; read each by its own route.
     */
    fun search(q: String, entity: String? = null, limit: Int? = null): List<JSONObject> {
        require(q.trim().length in 1..200) { "q is 1 to 200 characters" }
        limit?.let { require(it in 1..50) { "limit is 1 to 50" } }
        val items = call("GET", "search", mapOf("q" to q.trim(), "entity" to entity, "limit" to limit)).obj().optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    /** The workspace settings and their version, for [editSettings]. */
    fun settings(): Reply = call("GET", "settings")

    /** Edits settings (every field optional, unknown fields refused); strict route: may need a step-up (`403 auth.stepup`). */
    fun editSettings(changes: JSONObject, version: String? = null): Reply = call("PATCH", "settings", body = changes, ifMatch = version)

    // ---- internals --------------------------------------------------------------------------------------------------

    private fun reply(response: Response): Reply {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (response.isSuccessful) {
            if (json == null || !json.optBoolean("success", false)) throw AdukiException.Protocol("Response is not a success envelope")
            val etag = response.header("ETag")?.removePrefix("W/")?.trim('"')
            return Reply(response.code, json.opt("data").takeUnless { it == JSONObject.NULL }, etag)
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

    private fun hex(value: String): String {
        require(HEX.matches(value)) { "An id is sixteen lowercase hexadecimal digits" }
        return value
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody()
        val HEX = Regex("^[0-9a-f]{16}$")
    }
}
