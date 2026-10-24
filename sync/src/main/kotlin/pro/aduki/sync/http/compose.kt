package pro.aduki.sync.http

import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.models.Draft
import pro.aduki.core.models.Upload
import pro.aduki.net.http.Jmap
import pro.aduki.sync.outbox.Rejected
import java.io.File
import java.util.Base64

/**
 * Composer sends a [Draft] with JMAP: the attachments are uploaded, the
 * message is created in Drafts (`Email/set`) and submitted
 * (`EmailSubmission/set`), which files it in Sent and clears `$draft`; the
 * original of a reply or forward gets `$answered` or `$forwarded`.
 *
 * [key] must be stable across retries of one send: it becomes the
 * Message-ID, and a retry that finds a message with it (the first attempt
 * got through but its reply was lost) does not send again.
 *
 * Blocking; run it off the main thread.
 */
class Composer(private val jmap: Jmap) {

    // Identities and special mailboxes, read once.
    @Volatile private var setup: Setup? = null

    private data class Setup(val identities: List<JSONObject>, val drafts: String, val sent: String)

    /** Sends [draft] and returns the hex of the stored message. */
    fun send(draft: Draft, key: String): String {
        draft.validate()
        val setup = setup()
        val identity = identity(setup, draft.from)
        val from = draft.from ?: identity.getString("email")
        val messageId = "$key@${from.substringAfter('@')}"

        existing(messageId)?.let { (hex, draftLeft) ->
            if (!draftLeft) return hex
            submit(hex, identity, setup, draft, create = null)
            return hex
        }

        val email = JSONObject()
            .put("mailboxIds", JSONObject().put(setup.drafts, true))
            .put("keywords", JSONObject().put("\$draft", true).put("\$seen", true))
            .put("from", JSONArray().put(address(from, draft.name ?: identity.optString("name").ifBlank { null })))
            .put("to", addresses(draft.to))
            .put("cc", addresses(draft.cc))
            .put("bcc", addresses(draft.bcc))
            .put("subject", draft.subject)
            .put("messageId", JSONArray().put(messageId))
        threading(draft.reply ?: draft.forward, draft.reply != null)?.let { (inReplyTo, references) ->
            if (inReplyTo != null) email.put("inReplyTo", JSONArray().put(inReplyTo))
            if (references.isNotEmpty()) email.put("references", JSONArray(references))
        }
        val values = JSONObject().put("t", JSONObject().put("value", draft.text))
        email.put("textBody", JSONArray().put(JSONObject().put("partId", "t").put("type", "text/plain")))
        if (draft.html != null) {
            values.put("h", JSONObject().put("value", draft.html))
            email.put("htmlBody", JSONArray().put(JSONObject().put("partId", "h").put("type", "text/html")))
        }
        email.put("bodyValues", values)
        if (draft.attachments.isNotEmpty()) {
            email.put("attachments", JSONArray(draft.attachments.map { upload ->
                val blob = upload(upload)
                JSONObject()
                    .put("blobId", blob.id)
                    .put("type", upload.type)
                    .put("name", upload.name)
                    .put("disposition", "attachment")
            }))
        }
        return submit(null, identity, setup, draft, create = email)
    }

    // Creates (when [create] is given) and submits; returns the email hex.
    private fun submit(hex: String?, identity: JSONObject, setup: Setup, draft: Draft, create: JSONObject?): String {
        val account = jmap.account()
        val calls = mutableListOf<Jmap.Invocation>()
        if (create != null) {
            calls += Jmap.Invocation(
                "Email/set",
                JSONObject().put("accountId", account).put("create", JSONObject().put("d", create)),
                "c"
            )
        }
        val emailId = hex ?: "#d"
        calls += Jmap.Invocation(
            "EmailSubmission/set",
            JSONObject()
                .put("accountId", account)
                .put("create", JSONObject().put("s", JSONObject().put("identityId", identity.getString("id")).put("emailId", emailId)))
                .put(
                    "onSuccessUpdateEmail",
                    JSONObject().put(
                        "#s",
                        JSONObject().put("mailboxIds", JSONObject().put(setup.sent, true)).put("keywords/\$draft", JSONObject.NULL)
                    )
                ),
            "s"
        )
        val original = draft.reply ?: draft.forward
        if (original != null) {
            val keyword = if (draft.reply != null) "\$answered" else "\$forwarded"
            calls += Jmap.Invocation(
                "Email/set",
                JSONObject().put("accountId", account).put("update", JSONObject().put(original, JSONObject().put("keywords/$keyword", true))),
                "o"
            )
        }
        val replies = jmap.call(calls, USING)
        var made = hex
        replies.firstOrNull { it.id == "c" && it.name == "Email/set" }?.args?.let { set ->
            set.optJSONObject("notCreated")?.optJSONObject("d")?.let { throw refused("Email/set", it) }
            made = set.optJSONObject("created")?.optJSONObject("d")?.optString("id")
        }
        replies.firstOrNull { it.id == "s" && it.name == "EmailSubmission/set" }?.args?.let { set ->
            set.optJSONObject("notCreated")?.optJSONObject("s")?.let { throw refused("EmailSubmission/set", it) }
        }
        return made ?: throw Rejected("Email/set created no message")
    }

    // The message a previous attempt stored under [messageId]: its hex and whether it is still a draft.
    private fun existing(messageId: String): Pair<String, Boolean>? {
        val account = jmap.account()
        val replies = jmap.call(
            listOf(
                Jmap.Invocation(
                    "Email/query",
                    JSONObject().put("accountId", account).put("filter", JSONObject().put("header", JSONArray().put("Message-ID").put("<$messageId>"))),
                    "q"
                ),
                Jmap.Invocation(
                    "Email/get",
                    JSONObject()
                        .put("accountId", account)
                        .put("#ids", JSONObject().put("resultOf", "q").put("name", "Email/query").put("path", "/ids"))
                        .put("properties", JSONArray().put("id").put("keywords")),
                    "g"
                )
            )
        )
        val found = replies.first { it.id == "g" }.args.optJSONArray("list")?.optJSONObject(0) ?: return null
        val draft = found.optJSONObject("keywords")?.optBoolean("\$draft") ?: false
        return found.getString("id") to draft
    }

    // In-Reply-To and References for a reply to (or forward of) [hex].
    private fun threading(hex: String?, reply: Boolean): Pair<String?, List<String>>? {
        if (hex == null) return null
        val list = jmap.call(
            "Email/get",
            JSONObject().put("ids", JSONArray().put(hex)).put("properties", JSONArray().put("messageId").put("references"))
        ).optJSONArray("list")?.optJSONObject(0) ?: return null
        val id = list.optJSONArray("messageId")?.optString(0)?.ifBlank { null } ?: return null
        val references = strings(list.optJSONArray("references")) + id
        return (if (reply) id else null) to references
    }

    private fun upload(upload: Upload): Jmap.Blob {
        val bytes = upload.bytes
        return if (bytes != null) jmap.upload(bytes, upload.type) else jmap.upload(File(upload.path!!), upload.type)
    }

    private fun setup(): Setup {
        setup?.let { return it }
        val account = jmap.account()
        val replies = jmap.call(
            listOf(
                Jmap.Invocation("Identity/get", JSONObject().put("accountId", account), "i"),
                Jmap.Invocation("Mailbox/get", JSONObject().put("accountId", account).put("properties", JSONArray().put("id").put("role")), "m")
            ),
            USING
        )
        val identities = replies.first { it.id == "i" }.args.optJSONArray("list") ?: JSONArray()
        val boxes = replies.first { it.id == "m" }.args.optJSONArray("list") ?: JSONArray()
        fun role(name: String) = (0 until boxes.length()).map { boxes.getJSONObject(it) }
            .firstOrNull { it.optString("role") == name }?.getString("id")
            ?: throw Rejected("The account has no $name mailbox")
        val fresh = Setup((0 until identities.length()).map { identities.getJSONObject(it) }, role("drafts"), role("sent"))
        setup = fresh
        return fresh
    }

    private fun identity(setup: Setup, from: String?): JSONObject {
        if (setup.identities.isEmpty()) throw Rejected("The account has no sending identity")
        // No from: the default sender (`isDefault`), as the server's own send does.
        if (from == null) return setup.identities.firstOrNull { it.optBoolean("isDefault") } ?: setup.identities.first()
        return setup.identities.firstOrNull { it.optString("email").equals(from, ignoreCase = true) }
            ?: throw Rejected("Not allowed to send as $from")
    }

    private fun refused(method: String, error: JSONObject): Rejected {
        val type = error.optString("type")
        val description = error.optString("description").ifBlank { error.optJSONArray("properties")?.toString() ?: "" }
        return Rejected("Aduki refused $method: $type $description".trim())
    }

    companion object {
        private val USING = setOf(Jmap.CORE, Jmap.MAIL, Jmap.SUBMISSION)

        private fun address(email: String, name: String? = null): JSONObject =
            JSONObject().put("email", email).apply { if (name != null) put("name", name) }

        private fun addresses(list: List<String>): Any =
            if (list.isEmpty()) JSONObject.NULL else JSONArray(list.map { address(it.trim()) })

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }

        /** A draft as the outbox payload; bytes are kept base64. */
        fun encode(draft: Draft): JSONObject = JSONObject()
            .put("to", JSONArray(draft.to))
            .put("cc", JSONArray(draft.cc))
            .put("bcc", JSONArray(draft.bcc))
            .put("subject", draft.subject)
            .put("text", draft.text)
            .put("html", draft.html ?: JSONObject.NULL)
            .put("from", draft.from ?: JSONObject.NULL)
            .put("name", draft.name ?: JSONObject.NULL)
            .put("reply", draft.reply ?: JSONObject.NULL)
            .put("forward", draft.forward ?: JSONObject.NULL)
            .put("attachments", JSONArray(draft.attachments.map { a ->
                JSONObject()
                    .put("name", a.name)
                    .put("type", a.type)
                    .put("bytes", a.bytes?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
                    .put("path", a.path ?: JSONObject.NULL)
            }))

        /** Reads a payload written by [encode]. */
        fun decode(obj: JSONObject): Draft {
            fun opt(key: String) = if (obj.isNull(key)) null else obj.optString(key)
            val files = obj.optJSONArray("attachments") ?: JSONArray()
            return Draft(
                to = strings(obj.optJSONArray("to")),
                cc = strings(obj.optJSONArray("cc")),
                bcc = strings(obj.optJSONArray("bcc")),
                subject = obj.optString("subject"),
                text = obj.optString("text"),
                html = opt("html"),
                from = opt("from"),
                name = opt("name"),
                reply = opt("reply"),
                forward = opt("forward"),
                attachments = (0 until files.length()).map { i ->
                    val a = files.getJSONObject(i)
                    Upload(
                        name = a.optString("name"),
                        type = a.optString("type"),
                        bytes = if (a.isNull("bytes")) null else Base64.getDecoder().decode(a.getString("bytes")),
                        path = if (a.isNull("path")) null else a.getString("path")
                    )
                }
            )
        }
    }
}
