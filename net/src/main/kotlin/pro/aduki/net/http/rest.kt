package pro.aduki.net.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import java.io.IOException

/**
 * The plumbing of the small REST clients added in 1.0.0 (sender logos and
 * verification, contact extras, event meetings, billing): one request, the
 * success envelope read, and a refusal thrown as [AdukiException.Api] with
 * the server's kind (`401` as [AdukiException.Unauthorized]).
 */
internal object Rest {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val EMPTY = ByteArray(0).toRequestBody()

    fun url(endpoint: String, vararg segments: String): HttpUrl.Builder {
        val builder = endpoint.trimEnd('/').toHttpUrl().newBuilder()
        for (segment in segments) builder.addPathSegment(segment)
        return builder
    }

    fun request(method: String, url: HttpUrl, body: Any? = null, headers: Map<String, String> = emptyMap()): Request {
        val payload = body?.toString()?.toRequestBody(JSON)
        return Request.Builder().url(url).header("Accept", "application/json")
            .apply { for ((k, v) in headers) header(k, v) }
            .method(method, payload ?: if (method == "GET" || method == "HEAD") null else EMPTY)
            .build()
    }

    /** Sends [request] and gives the envelope's `data` (null for a `204` or a `data: null`) to [transform]. */
    fun <T> execute(client: OkHttpClient, request: Request, transform: (Any?) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AdukiException.Network("Network request failed for ${request.url.encodedPath}", e)
        }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) Profiles.fail(resp, raw)
            if (resp.code == 204 || raw.isBlank()) return transform(null)
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: throw AdukiException.Protocol("Not JSON from ${request.url.encodedPath}")
            if (json.has("success") && !json.optBoolean("success")) {
                throw AdukiException.Protocol("A failure in a ${resp.code} from ${request.url.encodedPath}")
            }
            val data = if (json.has("data")) json.opt("data").takeUnless { it == JSONObject.NULL } else json
            return try {
                transform(data)
            } catch (e: AdukiException) {
                throw e
            } catch (e: Exception) {
                throw AdukiException.Protocol("Unexpected response from ${request.url.encodedPath}", e)
            }
        }
    }

    fun obj(data: Any?): JSONObject = data as? JSONObject ?: throw AdukiException.Protocol("Expected an object in the response")

    /** The list in [data]: an array, or the `items` of an object. */
    fun items(data: Any?): List<JSONObject> {
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    fun text(o: JSONObject?, key: String): String? =
        if (o == null || !o.has(key) || o.isNull(key)) null else o.optString(key).ifBlank { null }

    fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.optString(it) }

    fun long(o: JSONObject?, key: String): Long? =
        if (o == null || !o.has(key) || o.isNull(key)) null else (o.opt(key) as? Number)?.toLong() ?: o.optString(key).toLongOrNull()

    fun instant(o: JSONObject?, key: String): java.time.Instant? {
        val raw = text(o, key) ?: return null
        return runCatching { java.time.Instant.parse(raw) }
            .recoverCatching { java.time.OffsetDateTime.parse(raw).toInstant() }
            .getOrNull()
    }
}
