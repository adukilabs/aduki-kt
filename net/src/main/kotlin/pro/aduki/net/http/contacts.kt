package pro.aduki.net.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.ContactRow
import pro.aduki.core.models.GroupRow
import pro.aduki.core.models.ImportReport
import pro.aduki.core.models.Listing
import pro.aduki.core.vcard.Vcard

/**
 * Contacts is the typed client for the REST address book (scopes
 * `contacts:read` and `contacts:write`). The tenant and user come from the
 * credential, never from the request.
 *
 * Writes send the vCard as it is (the server rebuilds it and writes one
 * `VERSION:4.0`), with the card's `FN` as the display name alongside.
 *
 * Calls are blocking; run them off the main thread.
 */
class Contacts(
    private val client: OkHttpClient,
    private val endpoint: String
) {

    private val mediaJson = "application/json; charset=utf-8".toMediaType()
    private val mediaVcard = "text/vcard; charset=utf-8".toMediaType()

    private fun url(vararg segments: String): HttpUrl.Builder {
        val builder = endpoint.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("user").addPathSegment("contacts")
        for (segment in segments) builder.addPathSegment(segment)
        return builder
    }

    /**
     * One page of the address book, [limit] rows (the server caps it at 200).
     * Pass the previous page's `next` as [after] (null on the last page); a
     * server that answers in page mode (no `next`, but `page` and `pages`)
     * is walked with [page].
     */
    fun list(after: String? = null, limit: Int = 200, page: Int? = null): Listing<ContactRow> {
        val builder = url().addQueryParameter("limit", limit.toString())
        if (!after.isNullOrBlank()) builder.addQueryParameter("after", after)
        if (page != null) builder.addQueryParameter("page", page.toString())
        return execute(Request.Builder().url(builder.build()).get().build()) { body -> Companion.page(JSONObject(body)) }
    }

    /**
     * Creates a contact from [vcard] (`POST /user/contacts`) tagged with
     * [groups]; returns it as stored. Pass a stable [key] when retrying, so
     * a retry replays the first result instead of creating a second card.
     */
    fun create(vcard: String, groups: List<String> = emptyList(), key: String? = null): ContactRow {
        val payload = JSONObject()
            .put("vcard", vcard)
            .put("name", name(vcard))
            .put("meta", JSONObject.NULL)
        if (groups.isNotEmpty()) payload.put("groups", JSONArray(groups))
        return execute(write(url(), "POST", payload, key)) { body -> row(JSONObject(body)) }
    }

    /** Replaces the vCard of [hex] (`PATCH /user/contacts/{hex}/vcard`). */
    fun replace(hex: String, vcard: String, key: String? = null) {
        val payload = JSONObject().put("vcard", vcard).put("name", name(vcard))
        execute(write(url(hex, "vcard"), "PATCH", payload, key)) { }
    }

    /** Sets the group tags of [hex] (`PATCH /user/contacts/{hex}/groups`); an empty list clears them. */
    fun groups(hex: String, groups: List<String>, key: String? = null) {
        execute(write(url(hex, "groups"), "PATCH", JSONObject().put("groups", JSONArray(groups)), key)) { }
    }

    /** One page of the members of [group] (`GET /user/contacts/group/{group}`). */
    fun members(group: String, limit: Int = 200, page: Int? = null): Listing<ContactRow> {
        val builder = url("group", group).addQueryParameter("limit", limit.toString())
        if (page != null) builder.addQueryParameter("page", page.toString())
        return execute(Request.Builder().url(builder.build()).get().build()) { body -> Companion.page(JSONObject(body)) }
    }

    /** The groups with their member counts, by name (`GET /user/contacts/groups`); empty groups count 0. */
    fun groups(): List<GroupRow> =
        execute(Request.Builder().url(url("groups").build()).get().build()) { body ->
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).map { items.getJSONObject(it) }.map { GroupRow(it.getString("name"), it.optInt("count")) }
        }

    /**
     * Creates the group [name] without members (`POST /user/contacts/groups`);
     * it stays listed until deleted. A group that exists already is
     * returned as it is (200 instead of 201).
     */
    fun createGroup(name: String, key: String? = null): GroupRow =
        execute(write(url("groups"), "POST", JSONObject().put("name", name), key)) { body ->
            val obj = JSONObject(body)
            GroupRow(obj.getString("name"), obj.optInt("count"))
        }

    /**
     * Renames [group] to [name] on every member, and the group itself when
     * it was created empty (`PATCH /user/contacts/group/{group}`). Returns
     * how many contacts changed: 0 for an empty or unknown group, never 404.
     */
    fun rename(group: String, name: String, key: String? = null): Int =
        execute(write(url("group", group), "PATCH", JSONObject().put("name", name), key)) { body ->
            val obj = JSONObject(body)
            obj.getString("group")
            obj.getInt("changed")
        }

    /**
     * Deletes [group] (`DELETE /user/contacts/group/{group}`): its members
     * lose it (the contacts stay). Returns how many contacts changed.
     */
    fun dissolve(group: String): Int =
        execute(Request.Builder().url(url("group", group).build()).delete().build()) { body -> JSONObject(body).getInt("changed") }

    /**
     * Merges [others] into [hex] in one server transaction
     * (`POST /user/contacts/{hex}/merge`): their groups join its own,
     * [vcard] (when given) replaces its vCard, and the others are deleted.
     * Returns the merged contact; a 404 means one of them is not there and
     * nothing changed.
     */
    fun merge(hex: String, others: List<String>, vcard: String? = null, key: String? = null): ContactRow {
        require(others.isNotEmpty()) { "others: name at least one contact" }
        val payload = JSONObject().put("others", JSONArray(others))
        if (vcard != null) payload.put("vcard", vcard)
        return execute(write(url(hex, "merge"), "POST", payload, key)) { body -> row(JSONObject(body)) }
    }

    /**
     * Imports a vCard file of one or more cards (`POST /user/contacts/import`,
     * `text/vcard`): a card whose UID is stored replaces that contact, or is
     * skipped when identical; any other is created. At most 5000 cards.
     */
    fun import(vcf: String, key: String? = null): ImportReport {
        val builder = Request.Builder().url(url("import").build()).post(vcf.toRequestBody(mediaVcard))
        if (!key.isNullOrBlank()) builder.header("Idempotency-Key", key)
        return execute(builder.build()) { body ->
            val obj = JSONObject(body)
            val errors = obj.optJSONArray("errors") ?: JSONArray()
            ImportReport(
                created = obj.getInt("created"),
                updated = obj.getInt("updated"),
                skipped = obj.getInt("skipped"),
                errors = (0 until errors.length()).map { errors.getJSONObject(it) }.map { it.getInt("index") to it.optString("error") }
            )
        }
    }

    /** Deletes [hex] (`DELETE /user/contacts/{hex}`). */
    fun delete(hex: String) {
        execute(Request.Builder().url(url(hex).build()).delete().build()) { }
    }

    private fun write(url: HttpUrl.Builder, method: String, payload: JSONObject, key: String?): Request {
        val builder = Request.Builder().url(url.build()).method(method, payload.toString().toRequestBody(mediaJson))
        if (!key.isNullOrBlank()) builder.header("Idempotency-Key", key)
        return builder.build()
    }

    private fun <T> execute(request: Request, transform: (String) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw AdukiException.Network("Network request failed for ${request.url}", e)
        }
        response.use { resp ->
            val raw = resp.body?.string() ?: ""
            if (resp.code == 401 || resp.code == 403) {
                throw AdukiException.Auth("Unauthorized access (${Envelope.failure(raw) ?: "HTTP ${resp.code}"})")
            }
            if (!resp.isSuccessful) {
                throw AdukiException.Network("HTTP error ${resp.code}: ${Envelope.failure(raw) ?: resp.message}", code = resp.code)
            }
            return try {
                transform(Envelope.data(raw))
            } catch (e: Exception) {
                throw AdukiException.Protocol("Unexpected response from ${request.url}", e)
            }
        }
    }

    companion object {
        /** Parses a `Page<ContactRow>`. */
        fun page(obj: JSONObject): Listing<ContactRow> {
            val items = obj.optJSONArray("items") ?: JSONArray()
            return Listing(
                items = (0 until items.length()).map { row(items.getJSONObject(it)) },
                total = obj.optLong("total"),
                next = if (obj.isNull("next")) null else obj.optString("next").ifEmpty { null },
                page = if (obj.has("page")) obj.optInt("page") else null,
                pages = if (obj.has("pages")) obj.optInt("pages") else null
            )
        }

        // The server overrides `FN` with the name sent; send the card's own.
        private fun name(vcard: String): String = runCatching { Vcard.parse(vcard).name }
            .getOrElse { throw IllegalArgumentException("Not a vCard 3.0 or 4.0 with a name", it) }

        private fun row(obj: JSONObject): ContactRow {
            val vcard = if (obj.isNull("vcard")) "" else obj.optString("vcard")
            val card = if (vcard.isNotBlank()) runCatching { Vcard.parse(vcard) }.getOrNull() else null
            return ContactRow(
                hex = obj.optString("hex"),
                etag = if (obj.isNull("etag")) "" else obj.optString("etag"),
                name = if (obj.isNull("name")) null else obj.optString("name"),
                emails = strings(obj.optJSONArray("emails")),
                phones = strings(obj.optJSONArray("phones")),
                groups = strings(obj.optJSONArray("groups")),
                addresses = card?.addresses ?: emptyList(),
                title = card?.title ?: "",
                birthday = card?.birthday ?: "",
                urls = card?.urls ?: emptyList(),
                nickname = card?.nickname ?: "",
                collected = card?.collected ?: if (obj.has("collected")) obj.optBoolean("collected", false) else false,
                created = if (obj.isNull("created")) 0L else Mail.timestamp(obj.optString("created")),
                uid = if (obj.isNull("uid")) "" else obj.optString("uid"),
                vcard = vcard,
                updated = if (obj.isNull("updated")) 0L else Mail.timestamp(obj.optString("updated"))
            )
        }

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { if (arr.isNull(it)) null else arr.optString(it) }
        }
    }
}
