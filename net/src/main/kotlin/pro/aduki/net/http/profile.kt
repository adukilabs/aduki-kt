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
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Avatar
import pro.aduki.core.models.AvatarImage
import pro.aduki.core.models.Plan
import pro.aduki.core.models.Profile
import pro.aduki.core.models.Role
import pro.aduki.core.models.Storage
import pro.aduki.core.models.TenantInfo
import java.io.IOException

/**
 * Profiles reads and edits the signed-in account's profile on the mail
 * server (`/user/profile`, `/user/avatar`, `/user/lookup/avatars`) and reads
 * the pooled storage quota from Aduki Store (`GET /v1/quota`).
 *
 * [endpoint] is the mail REST base (`https://mail.aduki.pro/v1`) and [client]
 * carries the mail credential. Failures are [AdukiException]s: a 401 is
 * [AdukiException.Unauthorized], any other refusal [AdukiException.Api] with
 * the envelope's kind (`locked` on a 403 when the tenant locks profiles,
 * see [AdukiException.Api.locked]), a transport failure
 * [AdukiException.Network]. Calls are blocking; run them off the main thread.
 */
object Profiles {

    /** The photo types the server accepts. */
    val TYPES = setOf("image/jpeg", "image/png", "image/webp")

    /** The largest photo the server accepts, bytes. */
    const val LIMIT = 1L shl 20

    /** The most addresses one [lookup] request may carry. */
    const val BATCH = 50

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** The signed-in account's profile (`GET /user/profile`). */
    fun get(client: OkHttpClient, endpoint: String): Profile {
        val request = Request.Builder().url(url(endpoint, "user", "profile").build()).get().build()
        return execute(client, request) { parse(it) }
    }

    /**
     * A photo (`GET /user/avatar`, or `GET /user/avatar/{hex}` for the
     * account [hex]). With [etag] the request is conditional: null when the
     * photo is still [etag] (304), and null when there is none or it is not
     * visible (404).
     */
    fun avatar(client: OkHttpClient, endpoint: String, hex: String? = null, etag: String? = null): AvatarImage? {
        val path = if (hex.isNullOrBlank()) url(endpoint, "user", "avatar") else url(endpoint, "user", "avatar", hex)
        val builder = Request.Builder().url(path.build()).get()
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", quoted(etag))
        val response = send(client, builder.build())
        response.use { resp ->
            if (resp.code == 304 || resp.code == 404) return null
            if (!resp.isSuccessful) fail(resp, resp.body?.string().orEmpty())
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw AdukiException.Protocol("Empty photo from ${resp.request.url.encodedPath}")
            val tag = resp.header("ETag")?.removePrefix("W/")?.trim('"').orEmpty()
            val type = resp.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            return AvatarImage(bytes, tag, type)
        }
    }

    /**
     * Sets the photo (`PUT /user/avatar`) to [bytes] of media [type]
     * (`image/jpeg`, `image/png` or `image/webp`, at most 1 MiB and 1024 by
     * 1024 pixels). Returns the profile with the new photo.
     */
    fun upload(client: OkHttpClient, endpoint: String, bytes: ByteArray, type: String): Profile {
        val media = type.substringBefore(';').trim().lowercase()
        if (media !in TYPES) throw AdukiException.Api(415, "input.type", "A photo is JPEG, PNG or WebP")
        if (bytes.isEmpty()) throw AdukiException.Api(400, "input.invalid", "The photo is empty")
        val request = Request.Builder()
            .url(url(endpoint, "user", "avatar").build())
            .put(bytes.toRequestBody(media.toMediaType()))
            .build()
        return execute(client, request) { parse(it) }
    }

    /** Removes the photo (`DELETE /user/avatar`). Returns the profile without it. */
    fun remove(client: OkHttpClient, endpoint: String): Profile {
        val request = Request.Builder().url(url(endpoint, "user", "avatar").build()).delete().build()
        return execute(client, request) { parse(it) }
    }

    /**
     * The photos of the accounts with [emails] that the caller may see
     * (`POST /user/lookup/avatars`), keyed by address as the server wrote it;
     * each [Avatar] carries its [Avatar.account]. Addresses without a visible
     * photo are absent. At most [BATCH] addresses per call.
     */
    fun lookup(client: OkHttpClient, endpoint: String, emails: List<String>): Map<String, Avatar> {
        require(emails.size <= BATCH) { "at most $BATCH addresses per lookup" }
        if (emails.isEmpty()) return emptyMap()
        val body = JSONObject().put("emails", JSONArray(emails)).toString()
        val request = Request.Builder()
            .url(url(endpoint, "user", "lookup", "avatars").build())
            .post(body.toRequestBody(JSON))
            .build()
        return execute(client, request) { text ->
            val items = items(text)
            val found = LinkedHashMap<String, Avatar>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val email = item.optString("email").ifBlank { null } ?: continue
                val avatar = item.optJSONObject("avatar")?.let { avatar(it, item.optString("hex")) } ?: continue
                found[email] = avatar
            }
            found
        }
    }

    /**
     * The pooled storage of the signed-in account's drive at Aduki Store
     * (`GET {base}/quota`, audience [audience]): `used` and `cap` (null when
     * unlimited). Null when there is no drive yet (404).
     */
    fun quota(id: Id, base: String = Endpoints.STORE, audience: String = Endpoints.STORE_AUDIENCE): Storage? {
        val url = "${base.trimEnd('/')}/quota"
        val response = try {
            id.call(audience) { token ->
                Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            }
        } catch (e: IOException) {
            throw AdukiException.Network("Store request failed for $url", e)
        }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.code == 404) return null
            if (!resp.isSuccessful) fail(resp, raw)
            val obj = runCatching { JSONObject(Envelope.data(raw)) }.getOrNull()
                ?: throw AdukiException.Protocol("Unexpected quota response")
            if (!obj.has("used")) throw AdukiException.Protocol("Quota without used")
            return Storage(
                used = obj.getLong("used"),
                limit = if (obj.isNull("cap")) null else obj.optLong("cap"),
                pooled = true
            )
        }
    }

    /** Parses the `data` of a profile reply. */
    internal fun parse(text: String): Profile {
        val obj = JSONObject(text)
        val hex = obj.getString("hex").ifBlank { throw AdukiException.Protocol("Profile without hex") }
        val tenant = obj.optJSONObject("tenant")?.let {
            TenantInfo(it.getString("hex"), string(it, "name"), string(it, "slug"))
        }
        val kind = when (string(obj, "kind")) {
            "personal" -> Profile.Kind.PERSONAL
            "member" -> Profile.Kind.MEMBER
            else -> if (tenant == null) Profile.Kind.PERSONAL else Profile.Kind.MEMBER
        }
        val role = obj.optJSONObject("role")?.let { Role(string(it, "label"), it.optBoolean("owner", false)) } ?: Role()
        val plan = obj.optJSONObject("plan")?.let { Plan(string(it, "code"), string(it, "state")) }
        val storage = obj.optJSONObject("storage")?.let {
            Storage(it.optLong("used", 0L), if (it.isNull("limit") || !it.has("limit")) null else it.getLong("limit"))
        } ?: Storage()
        return Profile(
            hex = hex,
            name = string(obj, "name"),
            email = string(obj, "email"),
            avatar = obj.optJSONObject("avatar")?.let { avatar(it, "") },
            kind = kind,
            tenant = tenant,
            role = role,
            plan = plan,
            storage = storage,
            editable = obj.optBoolean("editable", true)
        )
    }

    private fun avatar(obj: JSONObject, account: String): Avatar? {
        val hex = string(obj, "hex").ifBlank { return null }
        return Avatar(hex, Mail.timestamp(string(obj, "updated")), account)
    }

    // The lookup list: the envelope's `data` array, or `{data: [...]}` / `{items: [...]}` inside it.
    private fun items(text: String): JSONArray {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val obj = JSONObject(trimmed)
        return obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: JSONArray()
    }

    private fun string(obj: JSONObject, key: String): String = if (obj.isNull(key)) "" else obj.optString(key, "")

    private fun quoted(etag: String): String = if (etag == "*" || etag.startsWith("\"") || etag.startsWith("W/")) etag else "\"$etag\""

    private fun url(endpoint: String, vararg segments: String): HttpUrl.Builder {
        val builder = endpoint.trimEnd('/').toHttpUrl().newBuilder()
        for (segment in segments) builder.addPathSegment(segment)
        return builder
    }

    private fun send(client: OkHttpClient, request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw AdukiException.Network("Network request failed for ${request.url.encodedPath}", e)
    }

    private fun <T> execute(client: OkHttpClient, request: Request, transform: (String) -> T): T {
        send(client, request).use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) fail(resp, raw)
            val data = Envelope.data(raw).ifBlank { throw AdukiException.Protocol("Empty response from ${request.url.encodedPath}") }
            return try {
                transform(data)
            } catch (e: AdukiException) {
                throw e
            } catch (e: Exception) {
                throw AdukiException.Protocol("Unexpected response from ${request.url.encodedPath}", e)
            }
        }
    }

    // A refusal as an exception: 401 Unauthorized, everything else Api with the
    // envelope's kind (`error.kind`, else `error.code`, else `http.<status>`).
    internal fun fail(resp: Response, raw: String): Nothing {
        val error = runCatching { JSONObject(raw) }.getOrNull()?.optJSONObject("error")
        val kind = error?.optString("kind").orEmpty().ifBlank { error?.optString("code").orEmpty() }.ifBlank { "http.${resp.code}" }
        val message = error?.optString("message").orEmpty().ifBlank { "HTTP ${resp.code}" }
        if (resp.code == 401) throw AdukiException.Unauthorized("$kind: $message")
        throw AdukiException.Api(
            status = resp.code,
            kind = kind,
            message = message,
            details = error?.optJSONObject("details")?.toString(),
            retryAfter = Envelope.retryAfter(resp)
        )
    }
}
