package pro.aduki.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import java.io.File
import java.io.InputStream
import java.net.URLEncoder

/**
 * Jmap is the JMAP (RFC 8620) client of the Aduki mail host: the session,
 * batched method calls, and blob upload and download.
 *
 * JMAP ids are the REST hexes: an `Email` id is a message hex, a `Mailbox`
 * id a mailbox hex, so results apply to the local store directly.
 *
 * [client] is the authenticated client; [host] the mail host root without
 * `/v1`, e.g. `https://mail.aduki.pro`. Calls are blocking; run them off the
 * main thread.
 */
class Jmap(
    private val client: OkHttpClient,
    private val host: String
) {
    /**
     * Session is the part of `GET /jmap/session` the SDK uses: the primary
     * account and the URL templates.
     */
    data class Session(
        val account: String,
        val api: String,
        val upload: String,
        val download: String,
        val capabilities: Set<String> = emptySet()
    )

    /** One method call; [id] ties the responses to it. */
    data class Invocation(val name: String, val args: JSONObject, val id: String)

    /** One method response (a call can have several, e.g. an implicit `Email/set`). */
    data class Reply(val name: String, val args: JSONObject, val id: String)

    /** An uploaded blob. */
    data class Blob(val id: String, val type: String, val size: Long)

    @Volatile private var cached: Session? = null

    /** The session, fetched once; [refresh] fetches it again. */
    fun session(refresh: Boolean = false): Session {
        cached?.takeIf { !refresh }?.let { return it }
        val request = Request.Builder().url("${host.trimEnd('/')}/jmap/session").get().build()
        val obj = JSONObject(execute(request))
        val primary = obj.optJSONObject("primaryAccounts") ?: JSONObject()
        val account = primary.optString(MAIL).ifBlank { primary.optString(CORE) }
        if (account.isBlank()) throw AdukiException.Protocol("JMAP session has no mail account")
        val fresh = Session(
            account = account,
            api = obj.getString("apiUrl"),
            upload = obj.getString("uploadUrl"),
            download = obj.getString("downloadUrl"),
            capabilities = obj.optJSONObject("capabilities")?.keySet()?.toSet() ?: emptySet()
        )
        cached = fresh
        return fresh
    }

    /** The primary mail account id. */
    fun account(): String = session().account

    /**
     * Runs [calls] in one request and returns every response in order. A
     * method `error` response is thrown as [AdukiException.Api] with the JMAP
     * error type as its kind (status 400); per-object errors (`notCreated`,
     * `notUpdated`, ...) are left to the caller.
     */
    fun call(calls: List<Invocation>, using: Set<String> = USING): List<Reply> {
        val body = JSONObject()
            .put("using", JSONArray(using.toList()))
            .put("methodCalls", JSONArray(calls.map { JSONArray().put(it.name).put(it.args).put(it.id) }))
        val request = Request.Builder()
            .url(session().api)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val obj = JSONObject(execute(request))
        val responses = obj.optJSONArray("methodResponses") ?: throw AdukiException.Protocol("JMAP reply has no methodResponses")
        return (0 until responses.length()).map { i ->
            val item = responses.getJSONArray(i)
            val reply = Reply(item.getString(0), item.getJSONObject(1), item.getString(2))
            if (reply.name == "error") {
                val type = reply.args.optString("type", "serverFail")
                throw AdukiException.Api(400, type, reply.args.optString("description", type))
            }
            reply
        }
    }

    /** Runs one method and returns its first response's arguments. `accountId` is added. */
    fun call(name: String, args: JSONObject, using: Set<String> = USING): JSONObject {
        args.put("accountId", account())
        return call(listOf(Invocation(name, args, "0")), using).first { it.id == "0" }.args
    }

    /** Uploads [bytes] as a blob of [type] (RFC 8620 §6.1). */
    fun upload(bytes: ByteArray, type: String): Blob = upload(bytes.toRequestBody(type.toMediaType()), type)

    /** Uploads [file] as a blob of [type], streaming it. */
    fun upload(file: File, type: String): Blob = upload(file.asRequestBody(type.toMediaType()), type)

    private fun upload(body: RequestBody, type: String): Blob {
        val url = session().upload.replace("{accountId}", account())
        val obj = JSONObject(execute(Request.Builder().url(url).post(body).build()))
        return Blob(obj.getString("blobId"), obj.optString("type", type), obj.optLong("size"))
    }

    /**
     * Opens blob [blob] for reading; the caller closes the stream. [name] and
     * [type] only label the download.
     */
    fun download(blob: String, name: String = "blob", type: String = "application/octet-stream"): InputStream {
        val url = session().download
            .replace("{accountId}", encode(account()))
            .replace("{blobId}", encode(blob))
            .replace("{name}", encode(name.ifBlank { "blob" }))
            .replace("{type}", encode(type))
        val response = open(Request.Builder().url(url).get().build())
        return response.body?.byteStream() ?: run {
            response.close()
            throw AdukiException.Protocol("Empty download for blob $blob")
        }
    }

    private fun execute(request: Request): String = open(request).use { it.body?.string() ?: "" }

    // A successful response, still open; failures are closed and thrown.
    private fun open(request: Request): Response {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw AdukiException.Network("Network request failed for ${request.url}", e)
        }
        if (response.isSuccessful) return response
        response.use { resp ->
            val raw = resp.body?.string() ?: ""
            val why = Envelope.failure(raw) ?: problem(raw) ?: resp.message
            if (resp.code == 401 || resp.code == 403) throw AdukiException.Auth("Unauthorized access ($why)")
            throw AdukiException.Network("HTTP error ${resp.code}: $why", code = resp.code)
        }
    }

    // RFC 7807 problem details, which JMAP request-level errors use.
    private fun problem(raw: String): String? =
        runCatching { JSONObject(raw) }.getOrNull()?.let { it.optString("detail").ifBlank { it.optString("type") } }?.ifBlank { null }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val CORE = "urn:ietf:params:jmap:core"
        const val MAIL = "urn:ietf:params:jmap:mail"
        const val SUBMISSION = "urn:ietf:params:jmap:submission"
        const val VACATION = "urn:ietf:params:jmap:vacationresponse"
        const val SIEVE = "urn:ietf:params:jmap:sieve"

        /** Core and mail: enough for `Email`, `Mailbox` and `Thread`. */
        val USING = setOf(CORE, MAIL)

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
