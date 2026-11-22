package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import java.io.IOException
import java.util.Base64

/** The space the caller's Chats handshakes name: [kind] is `open` (the home space) or `org` (a workspace). */
data class ChatSpace(val tenant: String?, val kind: String?, val home: String?, val workspace: String?)

/** One of the caller's Chats devices; [created] and [seen] are Unix seconds. */
data class ChatDevice(
    val id: String,
    val name: String?,
    val platform: String?,
    val created: Long,
    val seen: Long,
    val group: String?,
    val current: Boolean
)

/** A Chats contact; [dm] is the open direct thread with them, [source] `act` or `contacts`. */
data class ChatContact(
    val account: String,
    val handle: String?,
    val name: String?,
    val consentedAt: Long,
    val source: String,
    val presence: String?,
    val dm: String?
)

/** A page of contacts; pass [next] as `after` for the page that follows (null on the last). */
data class ChatContacts(val contacts: List<ChatContact>, val next: String?)

/** A group's sealed name and photo: [blob] is ciphertext (null with [version] 0), its key travels inside MLS. */
class ChatThreadInfo(
    val thread: String,
    val version: Long,
    val blob: ByteArray?,
    val size: Long,
    val by: String?,
    val at: Long?
)

/** A thread's call. [started] is false when `startCall` found one in progress (nobody was rung again). */
data class ChatCall(
    val call: String,
    val kind: String,
    val state: String,
    val signal: String?,
    val room: String?,
    val thread: String?,
    val started: Boolean,
    val people: Int,
    val devices: Int
)

/** Whether calls are offered now, and a thread's calls in progress. */
data class ChatCalls(val available: Boolean, val items: List<ChatCall>)

/** The key group of a call without a thread (a meeting, a room): a hidden thread of the home space. */
data class ChatCallGroup(
    val thread: String,
    val call: String,
    val founder: Boolean,
    val founded: Boolean,
    val members: List<String>
)

/** An uploaded encrypted blob: [token] fetches it, for [ttl] seconds. */
data class ChatUpload(val id: String, val token: String, val ttl: Long)

/** The caller's stored media: [limit] is null without a quota. */
data class ChatUsage(val bytes: Long, val files: Long, val limit: Long?)

/** One of the caller's own uploads; [at] is Unix seconds. */
data class ChatOwnMedia(val id: String, val bytes: Long, val at: Long)

/** A page of the caller's uploads; pass [next] as `after` for the page that follows. */
data class ChatOwnMediaPage(val media: List<ChatOwnMedia>, val next: String?)

/**
 * Chats is the client for the plain HTTP routes of Aduki Chats
 * (`https://chat.aduki.pro`, audience `chat`): the space to name, the
 * caller's devices and contacts, a group's sealed name and photo, a thread's
 * calls, and encrypted media blobs.
 *
 * It is **not** a messaging client. Listing conversations, reading and
 * sending messages, read marks and typing travel over the `ae09` protocol
 * (QUIC or WebSocket, CBOR) and every message is MLS-encrypted on the
 * device. This SDK has neither yet, so nothing here can read or send a
 * message, and the blobs it moves are ciphertext it cannot open.
 *
 * Aduki Chats accepts only DPoP-bound access tokens of a person (no
 * delegation, no client token). [http] must carry the [Dpop] interceptor
 * and no credentials of its own. A `401` renews the token once through
 * [bearer] and the request is sent once more. An account that never bound a
 * Chats device is refused `403 forbidden` with `details.reason = "binding"`,
 * except by [space] and [devices]. Calls are blocking: run them off the
 * main thread.
 */
class Chats(
    private val http: OkHttpClient,
    private val bearer: Bearer,
    private val base: String = Endpoints.CHATS
) {
    /** The server's home space (`GET /.well-known/ae09/server`, no sign-in): what a personal account names; null without one. */
    fun home(): String? = Rest.text(obj(data(open("GET", ".well-known/ae09/server", signed = false))), "tenant")

    /** The space this account's handshakes name (`GET /_ae09/space`); answered before a device is bound too. */
    fun space(): ChatSpace {
        val o = obj(data(open("GET", "_ae09/space")))
        return ChatSpace(Rest.text(o, "tenant"), Rest.text(o, "kind"), Rest.text(o, "home"), Rest.text(o, "workspace"))
    }

    /** The caller's active devices, most recently seen first (`GET /_ae09/devices`); empty without a binding. */
    fun devices(): List<ChatDevice> = list(obj(data(open("GET", "_ae09/devices"))), "devices").map(::device)

    /** Renames one of the caller's devices (`POST /_ae09/devices/name`); [name] is 1 to 64 characters. Returns the name kept. */
    fun renameDevice(device: String, name: String): String {
        uuid(device, "device")
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 64) { "a device name has 1 to 64 characters" }
        val o = obj(data(open("POST", "_ae09/devices/name", body = json(JSONObject().put("device", device).put("name", trimmed)))))
        return Rest.text(o, "name") ?: trimmed
    }

    /** Removes one of the caller's devices (`POST /_ae09/devices/revoke`): it is signed out of Chats. */
    fun revokeDevice(device: String): Boolean {
        uuid(device, "device")
        return obj(data(open("POST", "_ae09/devices/revoke", body = json(JSONObject().put("device", device))))).optBoolean("revoked")
    }

    /** A page of the accounts the caller consents to (`GET /_ae09/contacts`), in account order; [limit] is 1 to 200. */
    fun contacts(after: String? = null, limit: Int? = null): ChatContacts {
        after?.let { uuid(it, "after") }
        limit?.let { require(it in 1..200) { "limit is 1 to 200" } }
        val o = obj(data(open("GET", "_ae09/contacts", mapOf("after" to after, "limit" to limit?.toString()))))
        return ChatContacts(list(o, "contacts").map(::contact), Rest.text(o, "next"))
    }

    /** Every contact, page by page, at most [max] of them. */
    fun allContacts(max: Int = 2000): List<ChatContact> {
        require(max >= 1) { "max is at least 1" }
        val all = ArrayList<ChatContact>()
        var after: String? = null
        while (all.size < max) {
            val page = contacts(after, 200)
            all += page.contacts
            after = page.next ?: break
            if (page.contacts.isEmpty()) break
        }
        return if (all.size > max) all.subList(0, max).toList() else all
    }

    /**
     * A group's sealed name and photo (`GET /_ae09/threads/meta`). Only for a
     * group the caller holds a seat in: `404` without one, `422` for a direct
     * message or a channel.
     */
    fun threadInfo(thread: String): ChatThreadInfo {
        uuid(thread, "thread")
        val o = obj(data(open("GET", "_ae09/threads/meta", mapOf("thread" to thread))))
        val blob = Rest.text(o, "blob")?.let { text ->
            runCatching { Base64.getUrlDecoder().decode(text.trimEnd('=')) }
                .getOrElse { throw AdukiException.Protocol("A group's info is not base64url") }
        }
        return ChatThreadInfo(
            Rest.text(o, "thread") ?: thread, Rest.long(o, "version") ?: 0, blob, Rest.long(o, "size") ?: 0,
            Rest.text(o, "by"), Rest.long(o, "at")
        )
    }

    /**
     * Stores a group's sealed info (`PUT /_ae09/threads/meta`): [sealed] is 1
     * byte to 256 KiB of ciphertext, [version] the stored one plus one
     * (otherwise `409 conflict` with the stored version in its details).
     */
    fun putThreadInfo(thread: String, version: Long, sealed: ByteArray): ChatThreadInfo {
        uuid(thread, "thread")
        require(version >= 1) { "version is the stored one plus one" }
        require(sealed.isNotEmpty() && sealed.size <= INFO) { "the sealed info is 1 byte to 256 KiB" }
        val o = obj(data(open("PUT", "_ae09/threads/meta", mapOf("thread" to thread, "version" to version.toString()), sealed.toRequestBody(BYTES))))
        return ChatThreadInfo(
            Rest.text(o, "thread") ?: thread, Rest.long(o, "version") ?: version, null,
            Rest.long(o, "size") ?: sealed.size.toLong(), null, Rest.long(o, "at")
        )
    }

    /** Whether calls are offered now and, with [thread], its calls in progress (`GET /_ae09/calls`). */
    fun calls(thread: String? = null): ChatCalls {
        thread?.let { uuid(it, "thread") }
        val o = obj(data(open("GET", "_ae09/calls", mapOf("thread" to thread))))
        return ChatCalls(o.optBoolean("available"), list(o, "items").map(::call))
    }

    /**
     * Starts a call in [thread] or returns the one in progress there
     * (`POST /_ae09/calls`); the same [idempotency] key (a UUID) again
     * returns the same call. To join, ask aduki Calls for a ticket.
     */
    fun startCall(thread: String, idempotency: String? = null): ChatCall {
        uuid(thread, "thread")
        idempotency?.let { uuid(it, "idempotency") }
        val headers = if (idempotency == null) emptyMap() else mapOf("Idempotency-Key" to idempotency)
        return call(obj(data(open("POST", "_ae09/calls", body = json(JSONObject().put("thread", thread)), headers = headers))))
    }

    /** The key group of [call], sixteen hex digits (`POST /_ae09/calls/groups`); only for someone in the call now. */
    fun callGroup(call: String): ChatCallGroup {
        require(HEX.matches(call)) { "call is a call id (16 hex digits)" }
        val o = obj(data(open("POST", "_ae09/calls/groups", body = json(JSONObject().put("call", call)))))
        return ChatCallGroup(
            o.optString("thread"), Rest.text(o, "call") ?: call, o.optBoolean("founder"), o.optBoolean("founded"),
            Rest.strings(o.optJSONArray("members"))
        )
    }

    /** Uploads [ciphertext] (`PUT /_ae09/media`). The server stores it as given: encrypt it first. */
    fun upload(ciphertext: ByteArray): ChatUpload {
        require(ciphertext.isNotEmpty()) { "nothing to upload" }
        val o = obj(data(open("PUT", "_ae09/media", body = ciphertext.toRequestBody(BYTES))))
        val token = Rest.text(o, "token") ?: throw AdukiException.Protocol("An upload answer without a token")
        return ChatUpload(o.optString("id"), token, Rest.long(o, "ttl") ?: 0)
    }

    /** The ciphertext behind a media [token] (`GET /_ae09/media?token=`); the token is the whole credential. */
    fun download(token: String): ByteArray {
        require(token.isNotBlank()) { "token is a media token" }
        open("GET", "_ae09/media", mapOf("token" to token), signed = false, headers = mapOf("Accept" to "application/octet-stream")).use { r ->
            if (!r.isSuccessful) refuse(r.code, runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull())
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Deletes the blob behind [token] (`DELETE /_ae09/media?token=`); the token is spent. Returns the blob id. */
    fun delete(token: String): String {
        require(token.isNotBlank()) { "token is a media token" }
        return obj(data(open("DELETE", "_ae09/media", mapOf("token" to token), signed = false))).optString("id")
    }

    /** The caller's stored media in bytes and files (`GET /_ae09/usage`). */
    fun usage(): ChatUsage {
        val o = obj(data(open("GET", "_ae09/usage")))
        return ChatUsage(Rest.long(o, "bytes") ?: 0, Rest.long(o, "files") ?: 0, Rest.long(o, "limit"))
    }

    /** A page of the caller's own uploads (`GET /_ae09/media/mine`). */
    fun ownMedia(after: String? = null): ChatOwnMediaPage {
        val o = obj(data(open("GET", "_ae09/media/mine", mapOf("after" to after))))
        val media = list(o, "media").map { ChatOwnMedia(it.optString("id"), Rest.long(it, "bytes") ?: 0, Rest.long(it, "at") ?: 0) }
        return ChatOwnMediaPage(media, Rest.text(o, "next"))
    }

    /** Deletes one of the caller's own uploads by its [id] (`DELETE /_ae09/media/mine`); the people it was sent to lose it. */
    fun deleteOwnMedia(id: String) {
        require(BLOB.matches(id)) { "id is a blob id from ownMedia" }
        val o = obj(data(open("DELETE", "_ae09/media/mine", mapOf("id" to id))))
        if (!o.optBoolean("deleted")) throw AdukiException.Protocol("Deleting an upload was not confirmed")
    }

    private fun device(o: JSONObject) = ChatDevice(
        o.optString("id"), Rest.text(o, "name"), Rest.text(o, "platform"), Rest.long(o, "created") ?: 0,
        Rest.long(o, "seen") ?: 0, Rest.text(o, "group"), o.optBoolean("current")
    )

    private fun contact(o: JSONObject) = ChatContact(
        o.optString("account"), Rest.text(o, "handle"), Rest.text(o, "name"), Rest.long(o, "consentedAt") ?: 0,
        Rest.text(o, "source") ?: "act", Rest.text(o, "presence"), Rest.text(o, "dm")
    )

    private fun call(o: JSONObject): ChatCall {
        val join = o.optJSONObject("join")
        val ringing = o.optJSONObject("ringing")
        return ChatCall(
            o.optString("call"), o.optString("kind"), o.optString("state"), Rest.text(join, "signal"), Rest.text(join, "room"),
            Rest.text(o, "thread"), o.optBoolean("started", true), ringing?.optInt("people") ?: 0, ringing?.optInt("devices") ?: 0
        )
    }

    /** Sends one request; a signed one carries the `chat` token and is sent once more with a renewed token on a `401`. */
    private fun open(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: RequestBody? = null,
        signed: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val url = "${base.trimEnd('/')}/$path".toHttpUrl().newBuilder().apply {
            for ((k, v) in query) if (v != null) addQueryParameter(k, v)
        }.build()
        fun build(token: String?) = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                for ((k, v) in headers) header(k, v)
            }
            .method(method, body ?: if (method == "POST" || method == "PUT") EMPTY else null)
            .build()
        try {
            if (!signed) return http.newCall(build(null)).execute()
            val sent = bearer.token(null)
            val first = http.newCall(build(sent)).execute()
            if (first.code != 401) return first
            first.close()
            return http.newCall(build(bearer.token(sent))).execute()
        } catch (e: IOException) {
            throw AdukiException.Network("Chats request failed: ${e.message}", e)
        }
    }

    private companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BYTES = "application/octet-stream".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody()
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val HEX = Regex("^[0-9a-f]{16}$")
        private val BLOB = Regex("^[0-9A-Za-z_-]{1,64}$")
        private const val INFO = 256 * 1024

        private fun json(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON)

        private fun uuid(value: String, what: String) = require(UUID.matches(value)) { "$what is a UUID" }

        private fun obj(data: Any?): JSONObject = data as? JSONObject ?: throw AdukiException.Protocol("Expected an object from Chats")

        private fun list(o: JSONObject, key: String): List<JSONObject> {
            val array = o.optJSONArray(key) ?: JSONArray()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }

        /** The envelope's `data` of a success; a refusal is thrown with the server's kind. */
        private fun data(response: Response): Any? = response.use { r ->
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
            if (!r.isSuccessful) refuse(r.code, json)
            if (r.code == 204) return@use null
            if (json == null || !json.optBoolean("success", false)) throw AdukiException.Protocol("Chats answered without a success envelope")
            json.opt("data").takeUnless { it == JSONObject.NULL }
        }

        private fun refuse(status: Int, json: JSONObject?): Nothing {
            val error = json?.optJSONObject("error")
            val kind = error?.optString("kind").orEmpty().ifBlank { "http.$status" }
            val message = error?.optString("message").orEmpty().ifBlank { "HTTP $status" }
            if (status == 401) throw AdukiException.Unauthorized("$kind: $message", null, kind)
            throw AdukiException.Api(status = status, kind = kind, message = message, details = error?.optJSONObject("details")?.toString())
        }
    }
}
