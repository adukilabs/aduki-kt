package pro.aduki.hermes.net.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.hermes.core.errors.HermesException
import pro.aduki.hermes.core.models.Address
import pro.aduki.hermes.core.models.FlagUpdate
import pro.aduki.hermes.core.models.Listing
import pro.aduki.hermes.core.models.MailChanges
import pro.aduki.hermes.core.models.MailboxRow
import pro.aduki.hermes.core.models.MessageRow
import pro.aduki.hermes.core.models.Moved
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Mail is the typed client for the Hermes REST mail endpoints: listing,
 * incremental sync (`/user/mail/changes`), flags, move, delete, send and
 * mailboxes.
 *
 * Calls are blocking; run them off the main thread.
 */
class Mail(
    private val client: OkHttpClient,
    private val endpoint: String
) {
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private fun url(vararg segments: String): HttpUrl.Builder {
        val builder = endpoint.trimEnd('/').toHttpUrl().newBuilder()
        for (segment in segments) {
            builder.addPathSegment(segment)
        }
        return builder
    }

    /** Newest messages in the inbox, `limit` per page; pass the previous page's `next` as [after]. */
    fun inbox(after: String? = null, limit: Int = 50): Listing<MessageRow> =
        list(cursor(url("user", "mail", "inbox"), after, limit))

    /** Messages of one mailbox (by hex), one page at a time. */
    fun folder(mailbox: String, page: Int = 1, limit: Int = 50): Listing<MessageRow> =
        list(paged(url("user", "mail", "folder", mailbox), page, limit))

    /** Messages of one conversation. */
    fun thread(thread: String, page: Int = 1, limit: Int = 50): Listing<MessageRow> =
        list(paged(url("user", "mail", "thread", thread), page, limit))

    /**
     * What changed in [mailbox] since [since] (a MODSEQ from a previous call,
     * or 0 for a first sync). Pass the [uidvalidity] last seen, if any: a
     * mismatch comes back as [MailChanges.reset].
     */
    fun changes(mailbox: String, since: Long, uidvalidity: Long? = null, limit: Int = 500): MailChanges {
        val builder = url("user", "mail", "changes")
            .addQueryParameter("mailbox", mailbox)
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
        if (uidvalidity != null && uidvalidity > 0) {
            builder.addQueryParameter("uidvalidity", uidvalidity.toString())
        }
        return execute(Request.Builder().url(builder.build()).get().build()) { body ->
            parseChanges(JSONObject(body))
        }
    }

    /** Adds and removes flags on one message; flags not named are untouched. */
    fun flags(hex: String, add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        val payload = JSONObject()
            .put("add", JSONArray(add))
            .put("remove", JSONArray(remove))
        val request = Request.Builder()
            .url(url("user", "mail", hex, "flags").build())
            .patch(payload.toString().toRequestBody(mediaJson))
            .build()
        execute(request) { }
    }

    /** Moves one message to another mailbox; the id stays, the UID changes. */
    fun move(hex: String, mailbox: String): Moved {
        val payload = JSONObject().put("mailbox", mailbox)
        val request = Request.Builder()
            .url(url("user", "mail", hex, "mailbox").build())
            .patch(payload.toString().toRequestBody(mediaJson))
            .build()
        return execute(request) { body ->
            val obj = JSONObject(body)
            Moved(
                hex = obj.optString("hex"),
                mailbox = obj.optString("mailbox"),
                uid = obj.optLong("uid"),
                modseq = obj.optLong("modseq")
            )
        }
    }

    /** Deletes one message. */
    fun delete(hex: String) {
        val request = Request.Builder()
            .url(url("user", "mail", hex).build())
            .delete()
            .build()
        execute(request) { }
    }

    /**
     * Sends a plain-text message and returns the server's id for the copy
     * filed in Sent. [from] defaults to the account's own address. Pass a
     * stable [idempotencyKey] when retrying, so a retry replays the first
     * result instead of sending twice.
     */
    fun send(
        to: List<String>,
        subject: String,
        text: String,
        cc: List<String> = emptyList(),
        from: String? = null,
        idempotencyKey: String? = null
    ): String {
        val payload = JSONObject()
            .put("to", JSONArray(to))
            .put("cc", JSONArray(cc))
            .put("subject", subject)
            .put("text", text)
        if (!from.isNullOrBlank()) {
            payload.put("from", from)
        }
        val builder = Request.Builder()
            .url(url("user", "mail", "send").build())
            .post(payload.toString().toRequestBody(mediaJson))
        if (!idempotencyKey.isNullOrBlank()) {
            builder.header("Idempotency-Key", idempotencyKey)
        }
        return execute(builder.build()) { body -> JSONObject(body).getString("hex") }
    }

    /** The account's mailboxes. */
    fun mailboxes(page: Int = 1, limit: Int = 200): Listing<MailboxRow> {
        val request = Request.Builder().url(paged(url("user", "mailbox"), page, limit)).get().build()
        return execute(request) { body ->
            listing(JSONObject(body)) { item ->
                MailboxRow(
                    hex = item.optString("hex"),
                    name = item.optString("name"),
                    delimiter = item.optString("delimiter", "."),
                    flags = strings(item.optJSONArray("flags")),
                    uidvalidity = item.optLong("uidvalidity"),
                    uidnext = item.optLong("uidnext"),
                    messages = item.optLong("messages"),
                    unread = item.optLong("unread")
                )
            }
        }
    }

    private fun cursor(builder: HttpUrl.Builder, after: String?, limit: Int): HttpUrl {
        builder.addQueryParameter("limit", limit.toString())
        if (!after.isNullOrBlank()) {
            builder.addQueryParameter("after", after)
        }
        return builder.build()
    }

    private fun paged(builder: HttpUrl.Builder, page: Int, limit: Int): HttpUrl =
        builder
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()

    private fun list(url: HttpUrl): Listing<MessageRow> =
        execute(Request.Builder().url(url).get().build()) { body ->
            listing(JSONObject(body), ::parseRow)
        }

    private fun <T> execute(request: Request, transform: (String) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw HermesException.Network("Network request failed for ${request.url}", e)
        }
        response.use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw HermesException.Auth("Unauthorized access (HTTP ${resp.code})")
            }
            if (!resp.isSuccessful) {
                throw HermesException.Network("HTTP error ${resp.code}: ${resp.message}", code = resp.code)
            }
            val body = resp.body?.string() ?: ""
            return try {
                transform(body)
            } catch (e: HermesException) {
                throw e
            } catch (e: Exception) {
                throw HermesException.Protocol("Unexpected response from ${request.url}", e)
            }
        }
    }

    companion object {
        /** Parses one list row (or one `created` entry). */
        fun parseRow(obj: JSONObject): MessageRow {
            val mailbox = obj.optJSONObject("mailbox")
            return MessageRow(
                hex = obj.optString("hex"),
                uid = obj.optLong("uid"),
                subject = text(obj, "subject"),
                sender = text(obj, "sender"),
                size = obj.optLong("size"),
                flags = strings(obj.optJSONArray("flags")),
                thread = text(obj, "thread").ifEmpty { null },
                spam = if (obj.isNull("spam")) null else obj.optDouble("spam"),
                received = timestamp(text(obj, "internaldate")),
                preview = text(obj, "preview"),
                from = obj.optJSONObject("from")?.let(::address),
                to = obj.optJSONArray("to")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::address) }
                } ?: emptyList(),
                hasAttachment = obj.optBoolean("has_attachment"),
                mailbox = mailbox?.optString("hex") ?: text(obj, "mailbox"),
                mailboxName = mailbox?.let { text(it, "name") } ?: "",
                modseq = obj.optLong("modseq")
            )
        }

        /** Parses a `/user/mail/changes` response. */
        fun parseChanges(obj: JSONObject): MailChanges {
            val created = obj.optJSONArray("created") ?: JSONArray()
            val updated = obj.optJSONArray("updated") ?: JSONArray()
            val vanished = obj.optJSONArray("vanished") ?: JSONArray()
            return MailChanges(
                mailbox = obj.optString("mailbox"),
                uidvalidity = obj.optLong("uidvalidity"),
                modseq = obj.optLong("modseq"),
                reset = obj.optBoolean("reset"),
                more = obj.optBoolean("more"),
                created = (0 until created.length()).map { parseRow(created.getJSONObject(it)) },
                updated = (0 until updated.length()).map {
                    val u = updated.getJSONObject(it)
                    FlagUpdate(
                        hex = u.optString("hex"),
                        uid = u.optLong("uid"),
                        flags = strings(u.optJSONArray("flags")),
                        modseq = u.optLong("modseq")
                    )
                },
                vanished = (0 until vanished.length()).map { vanished.getLong(it) }
            )
        }

        /**
         * Server timestamps are UTC, usually without an offset
         * (`2026-09-26T09:02:20.658460`); an explicit offset is honoured.
         * Returns epoch milliseconds, or 0 when absent or unparseable.
         */
        fun timestamp(value: String): Long {
            if (value.isBlank()) return 0L
            return try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) {
                try {
                    OffsetDateTime.parse(value).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    try {
                        Instant.parse(value).toEpochMilli()
                    } catch (_: Exception) {
                        0L
                    }
                }
            }
        }

        private fun <T> listing(obj: JSONObject, item: (JSONObject) -> T): Listing<T> {
            val items = obj.optJSONArray("items") ?: JSONArray()
            return Listing(
                items = (0 until items.length()).map { item(items.getJSONObject(it)) },
                total = obj.optLong("total"),
                next = text(obj, "next").ifEmpty { null },
                page = if (obj.has("page")) obj.optInt("page") else null,
                pages = if (obj.has("pages")) obj.optInt("pages") else null
            )
        }

        private fun address(obj: JSONObject): Address =
            Address(name = text(obj, "name").ifEmpty { null }, email = obj.optString("email"))

        /** A string field, with JSON null and absence both as "". */
        private fun text(obj: JSONObject, key: String): String =
            if (obj.isNull(key)) "" else obj.optString(key)

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { if (arr.isNull(it)) null else arr.optString(it) }
        }
    }
}
