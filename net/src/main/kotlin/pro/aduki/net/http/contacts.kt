package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.ContactRow
import pro.aduki.core.models.Listing

/**
 * Contacts is the typed client for the REST address book
 * (`GET /user/contacts`, scope `contacts:read`). The tenant and user come
 * from the credential, never from the request.
 *
 * Calls are blocking; run them off the main thread.
 */
class Contacts(
    private val client: OkHttpClient,
    private val endpoint: String
) {

    /**
     * One page of the address book, [limit] rows (the server caps it at 200);
     * pass the previous page's `next` as [after].
     */
    fun list(after: String? = null, limit: Int = 200): Listing<ContactRow> {
        val builder = endpoint.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("user").addPathSegment("contacts")
            .addQueryParameter("limit", limit.toString())
        if (!after.isNullOrBlank()) builder.addQueryParameter("after", after)
        val request = Request.Builder().url(builder.build()).get().build()
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
                page(JSONObject(Envelope.data(raw)))
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
                next = if (obj.isNull("next")) null else obj.optString("next").ifEmpty { null }
            )
        }

        private fun row(obj: JSONObject) = ContactRow(
            hex = obj.optString("hex"),
            etag = if (obj.isNull("etag")) "" else obj.optString("etag"),
            name = if (obj.isNull("name")) null else obj.optString("name"),
            emails = strings(obj.optJSONArray("emails")),
            phones = strings(obj.optJSONArray("phones")),
            groups = strings(obj.optJSONArray("groups")),
            created = if (obj.isNull("created")) 0L else Mail.timestamp(obj.optString("created"))
        )

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { if (arr.isNull(it)) null else arr.optString(it) }
        }
    }
}
