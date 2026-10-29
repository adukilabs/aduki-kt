package pro.aduki.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Message
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Worker
import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Body
import pro.aduki.core.models.Draft
import pro.aduki.sync.http.Composer
import java.time.Instant
import java.util.UUID
import pro.aduki.state.repository.Conversation
import pro.aduki.sync.engine.Content as ContentEngine
import pro.aduki.sync.http.JmapContentTransport
import java.io.File
import java.io.InputStream
import pro.aduki.state.repository.Mail as MailRepo
import pro.aduki.sync.http.HttpMailboxTransport

/**
 * Mail service providing high-level operations for mailboxes and messages.
 */
class Mail internal constructor(
    private val client: Aduki,
    private val manager: Manager? = null,
    private val repo: MailRepo? = null,
    private val worker: Worker? = null,
    private val content: ContentEngine? = null
) {
    // Writes need the local store: without one they would vanish silently.
    private fun outbox(): Manager = manager ?: throw IllegalStateException("no local store")

    /**
     * Sends an email message: writes optimistically to local store, journals outbox action, and flushes.
     * Throws [IllegalStateException] without a local store (`Aduki.Builder.store`).
     */
    suspend fun send(to: List<String>, subject: String, body: String, mailbox: String = "outbox"): Message =
        send(Draft(to = to, subject = subject, text = body), mailbox)

    /**
     * Sends [draft] through the outbox: the message shows at once in
     * [mailbox] under a placeholder hex, replaced by the server's when sent.
     * A plain text draft goes over `POST /user/mail/send`; one with bcc,
     * HTML, attachments, a display name, or a reply or forward goes over
     * JMAP. Both are safe to retry: a send is never made twice.
     */
    suspend fun send(draft: Draft, mailbox: String = "outbox"): Message {
        draft.validate()
        val msg = Message(
            hex = "msg_${UUID.randomUUID()}",
            mailbox = mailbox,
            threadId = "",
            fromEmail = draft.from ?: "",
            to = draft.to.joinToString(", "),
            cc = draft.cc.joinToString(", "),
            bcc = draft.bcc.joinToString(", "),
            subject = draft.subject,
            preview = draft.text.take(120),
            hasAttachment = draft.attachments.isNotEmpty(),
            created = System.currentTimeMillis(),
            dirty = true
        )
        if (draft.plain()) {
            val raw = JSONObject()
                .put("to", JSONArray(draft.to))
                .put("cc", JSONArray(draft.cc))
                .put("subject", draft.subject)
                .put("text", draft.text)
            draft.from?.let { raw.put("from", it) }
            outbox().send(msg, raw.toString().toByteArray(Charsets.UTF_8))
        } else {
            outbox().send(msg, Composer.encode(draft).toString().toByteArray(Charsets.UTF_8), "compose")
        }
        worker?.drain()
        return msg
    }

    /**
     * Toggles a flag bitmask optimistically on a message.
     */
    suspend fun flag(hex: String, flag: Int) {
        outbox().flag(hex, flag)
    }

    /**
     * Moves a message optimistically to a destination mailbox.
     */
    suspend fun move(hex: String, dest: String) {
        outbox().move(hex, dest)
    }

    /**
     * Removes or marks a message as deleted optimistically.
     */
    suspend fun remove(hex: String) {
        outbox().remove(hex)
        content?.forget(listOf(hex))
    }

    /**
     * Sets ([on]) or clears [flag] (`Message.SEEN`, `FLAGGED`, ...) on a
     * message, optimistically and queued. Idempotent: nothing is queued when
     * the flag already is so.
     */
    suspend fun setFlag(hex: String, flag: Int, on: Boolean) {
        outbox().set(hex, flag, on)
    }

    /**
     * Sets or clears [flag] on many messages in one queued action; messages
     * already so are left alone.
     */
    suspend fun setFlags(hexes: List<String>, flag: Int, on: Boolean) {
        outbox().set(hexes, flag, on)
    }

    /** Moves many messages to [dest] in one queued action. */
    suspend fun move(hexes: List<String>, dest: String) {
        outbox().move(hexes, dest)
    }

    /** Deletes many messages in one queued action. */
    suspend fun remove(hexes: List<String>) {
        outbox().remove(hexes)
        content?.forget(hexes)
    }

    /** Marks every unread message of [mailbox] (a hex) read. */
    suspend fun markAllRead(mailbox: String) {
        setFlags(store().unseen(mailbox), Message.SEEN, true)
    }

    /**
     * Creates a mailbox, under [parent] (a mailbox hex) when given. It shows
     * at once under a placeholder hex (`Mailbox.pending()`), which becomes
     * the server's hex when the queued create is sent; messages moved into
     * it meanwhile follow.
     */
    suspend fun createMailbox(name: String, parent: String? = null): Mailbox {
        val box = outbox().createMailbox(name, parent)
        worker?.drain()
        return box
    }

    /** Renames a mailbox, optimistically and queued. */
    suspend fun renameMailbox(hex: String, name: String) {
        outbox().renameMailbox(hex, name)
    }

    /** Moves a mailbox under [parent] (null or empty for top-level). */
    suspend fun moveMailbox(hex: String, parent: String? = null) {
        outbox().moveMailbox(hex, parent)
        worker?.drain()
    }

    /** Deletes a mailbox and the messages in it, optimistically and queued. */
    suspend fun deleteMailbox(hex: String) {
        outbox().deleteMailbox(hex)
    }

    /**
     * Snoozes a message until [until]: it moves to the "Snoozed" mailbox
     * (created when missing) and gets the keyword `$snoozed-<epoch seconds>`.
     * Both sync to the server, so every device sees the snooze; the first
     * device to sync after [until] ([Sync.all] calls [wake]) brings it back
     * to the inbox, unread. The server itself does not wake messages.
     */
    suspend fun snooze(hex: String, until: Instant) {
        val queue = outbox()
        val msg = queue.message(hex) ?: throw IllegalArgumentException("Message not found: $hex")
        val stale = msg.keywordList().filter { it.startsWith(Message.SNOOZED) }
        queue.keywords(hex, add = listOf(Message.SNOOZED + until.epochSecond), remove = stale)
        queue.move(hex, snoozeBox())
    }

    /** Ends a snooze now: back to the inbox, unread, keyword removed. */
    suspend fun unsnooze(hex: String) {
        val queue = outbox()
        val msg = queue.message(hex) ?: throw IllegalArgumentException("Message not found: $hex")
        val inbox = store().listMailboxes().firstOrNull { it.role == "inbox" }?.hex
            ?: throw IllegalStateException("no inbox: sync the mailbox list first")
        queue.keywords(hex, remove = msg.keywordList().filter { it.startsWith(Message.SNOOZED) })
        queue.set(hex, Message.SEEN, false)
        if (msg.mailbox != inbox) queue.move(hex, inbox)
    }

    /**
     * Brings back every snoozed message whose time has come; returns how
     * many. [Sync.all] calls it after each sync.
     */
    suspend fun wake(now: Instant = Instant.now()): Int {
        val local = repo ?: return 0
        val due = local.listMailboxes().filter(::snoozing)
            .flatMap { local.listMessages(it.hex) }
            .filter { it.snoozed() in 1..now.toEpochMilli() }
        due.forEach { unsnooze(it.hex) }
        return due.size
    }

    // The "Snoozed" mailbox hex, created when missing.
    private suspend fun snoozeBox(): String =
        store().listMailboxes().firstOrNull(::snoozing)?.hex ?: createMailbox(SNOOZE).hex

    private fun snoozing(box: Mailbox): Boolean = box.name.substringAfterLast('/').substringAfterLast('.').equals(SNOOZE, ignoreCase = true)

    private fun store(): MailRepo = repo ?: throw IllegalStateException("no local store")

    /**
     * Returns a hot StateFlow observing messages in a mailbox.
     */
    fun observe(mailboxHex: String): StateFlow<List<Message>>? {
        return repo?.messages(mailboxHex)
    }

    /**
     * The mailboxes in the local store now. Use it right after a write or a
     * sync: [mailboxes] is fed by a store observer, so its `value` can trail a
     * write by a moment.
     */
    fun listMailboxes(): List<Mailbox> = repo?.listMailboxes() ?: emptyList()

    /** The local mailbox with [hex], or null. */
    fun getMailbox(hex: String): Mailbox? {
        val target = manager?.box(hex) ?: hex
        return manager?.mailbox(target) ?: repo?.getMailbox(target)
    }

    /**
     * The hex of the mailbox with [role] (`inbox`, `sent`, `drafts`, `trash`,
     * `junk`, `archive`) in the local store now, or null.
     */
    fun resolve(role: String): String? = listMailboxes().firstOrNull { it.role == role.lowercase() }?.hex

    /**
     * Returns a hot StateFlow observing all mailboxes. Eventually consistent:
     * its `value` can trail a write by a moment; [listMailboxes] reads the
     * store now.
     */
    fun mailboxes(): StateFlow<List<Mailbox>>? {
        return repo?.mailboxes()
    }

    /**
     * The full body of message [hex]: text and HTML parts and the attachment
     * list (JMAP `Email/get`). With a local store it is fetched once and then
     * read offline; [refresh] fetches it again.
     */
    suspend fun body(hex: String, refresh: Boolean = false): Body =
        content?.body(hex, refresh) ?: JmapContentTransport(client.jmap).fetch(hex)

    /** The attachments of message [hex] (from its body). */
    suspend fun attachments(hex: String): List<Attachment> = body(hex).attachments

    /**
     * Opens [attachment] for reading (a download, blocking: call it off the
     * main thread). The caller closes the stream.
     */
    fun open(attachment: Attachment): InputStream =
        client.jmap.download(attachment.blob, attachment.name, attachment.type.ifBlank { "application/octet-stream" })

    /** Downloads [attachment] into [file] and returns it. */
    suspend fun save(attachment: Attachment, file: File): File = withContext(Dispatchers.IO) {
        open(attachment).use { input -> file.outputStream().use { input.copyTo(it) } }
        file
    }

    /**
     * Resolves RFC 5322 headers for message [hex].
     */
    suspend fun headers(hex: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val reply = client.jmap.call(
            "Email/get",
            JSONObject()
                .put("ids", JSONArray(listOf(hex)))
                .put("properties", JSONArray(listOf("headers")))
        )
        val list = reply.optJSONArray("list") ?: return@withContext emptyList()
        if (list.length() == 0) return@withContext emptyList()
        val item = list.getJSONObject(0)
        val arr = item.optJSONArray("headers") ?: return@withContext emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out.add(obj.getString("name") to obj.optString("value"))
        }
        out
    }

    /**
     * Resolves the full RFC 5322 raw message source for message [hex].
     */
    suspend fun source(hex: String): String = withContext(Dispatchers.IO) {
        val reply = client.jmap.call(
            "Email/get",
            JSONObject()
                .put("ids", JSONArray(listOf(hex)))
                .put("properties", JSONArray(listOf("blobId")))
        )
        val list = reply.optJSONArray("list") ?: return@withContext ""
        if (list.length() == 0) return@withContext ""
        val blobId = list.getJSONObject(0).optString("blobId").ifBlank { return@withContext "" }
        client.jmap.download(blobId).use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    /**
     * Mutes or unmutes a thread by threadId or message hex, setting the `$muted` keyword.
     */
    suspend fun mute(threadId: String, on: Boolean = true) {
        val queue = outbox()
        val local = repo
        val messages = local?.listThread(threadId)?.ifEmpty { null }
            ?: listOfNotNull(queue.message(threadId))
        if (messages.isEmpty()) {
            if (on) queue.keywords(threadId, add = listOf(Message.MUTED))
            else queue.keywords(threadId, remove = listOf(Message.MUTED))
        } else {
            for (msg in messages) {
                if (on) {
                    if (!msg.muted()) queue.keywords(msg.hex, add = listOf(Message.MUTED))
                } else {
                    if (msg.muted()) queue.keywords(msg.hex, remove = listOf(Message.MUTED))
                }
            }
        }
        worker?.drain()
    }

    /**
     * Saves a draft message in the Drafts mailbox and journals outbox sync.
     */
    suspend fun saveDraft(to: List<String>, subject: String, body: String, replace: String? = null): Message =
        saveDraft(Draft(to = to, subject = subject, text = body), replace)

    /**
     * Saves a draft message in the Drafts mailbox and journals outbox sync.
     * When [replace] is given, marks the replaced draft deleted.
     */
    suspend fun saveDraft(draft: Draft, replace: String? = null): Message {
        val queue = outbox()
        val draftsBox = repo?.listMailboxes()?.firstOrNull { it.role == "drafts" }?.hex
            ?: repo?.listMailboxes()?.firstOrNull { it.name.equals("Drafts", ignoreCase = true) }?.hex
            ?: "drafts"
        val msg = queue.saveDraft(
            subject = draft.subject,
            to = draft.to,
            cc = draft.cc,
            bcc = draft.bcc,
            text = draft.text,
            html = draft.html,
            mailbox = draftsBox,
            replace = replace
        )
        content?.put(msg.hex, draft.text, draft.html.orEmpty())
        worker?.drain()
        return msg
    }

    /**
     * Reads a draft message and its body content as a [Draft].
     */
    suspend fun openDraft(hex: String): Draft {
        val msg = repo?.get(hex) ?: outbox().message(hex) ?: throw IllegalArgumentException("Draft not found: $hex")
        val b = try { body(hex) } catch (_: Exception) { Body() }
        return Draft(
            to = msg.recipients(),
            cc = msg.ccList(),
            bcc = msg.bccList(),
            subject = msg.subject,
            text = if (b.text.isNotBlank()) b.text else msg.preview,
            html = b.html.ifBlank { null }
        )
    }

    /**
     * Synced messages matching [query] (subject, sender, recipients,
     * preview), newest first; only [mailbox] (a hex) when given. Offline.
     * [find] asks the server for messages not synced yet.
     */
    fun search(query: String, mailbox: String? = null): StateFlow<List<Message>>? {
        return repo?.search(query, mailbox)
    }

    /**
     * Searches on the server (`GET /user/mail/search/{q}`), including
     * mailboxes and messages not synced locally. With [store] = true,
     * matching messages (and their mailboxes if unknown) are loaded into
     * the local store so they can be flagged, moved, or opened in thread view.
     */
    suspend fun find(
        query: String,
        mailbox: String? = null,
        page: Int = 1,
        limit: Int = 50,
        store: Boolean = false
    ): List<Message> = withContext(Dispatchers.IO) {
        val items = client.mailApi.search(query, mailbox, page, limit).items.map { HttpMailboxTransport.message(it) }
        if (store) {
            items.forEach { persist(it) }
        }
        items
    }

    /**
     * Loads message [hex] (and its mailbox row if unknown) into the store.
     * If already stored, returns the local message. The fetched message syncs
     * like any other afterwards.
     */
    suspend fun fetch(hex: String): Message = withContext(Dispatchers.IO) {
        val target = hex.trim()
        require(target.isNotEmpty()) { "message hex is required" }
        val existing = repo?.get(target) ?: manager?.message(target)
        if (existing != null) return@withContext existing

        // Try JMAP Email/get first
        val fromJmap = runCatching {
            val args = JSONObject()
                .put("ids", JSONArray().put(target))
                .put("properties", JSONArray(listOf("id", "threadId", "mailboxIds", "keywords", "size", "receivedAt", "from", "to", "subject", "preview", "hasAttachment")))
            val reply = client.jmap.call("Email/get", args)
            val obj = reply.optJSONArray("list")?.optJSONObject(0) ?: return@runCatching null
            val mboxes = obj.optJSONObject("mailboxIds")
            val mailboxHex = mboxes?.keys()?.asSequence()?.firstOrNull() ?: ""
            val fromObj = obj.optJSONArray("from")?.optJSONObject(0)
            val toArr = obj.optJSONArray("to") ?: JSONArray()
            val toStr = (0 until toArr.length()).mapNotNull { toArr.optJSONObject(it)?.optString("email") }.joinToString(", ")
            val kwObj = obj.optJSONObject("keywords") ?: JSONObject()
            val kwList = mutableListOf<String>()
            var flags = 0
            kwObj.keys().forEach { k ->
                when (k.lowercase()) {
                    "\$seen" -> flags = flags or Message.SEEN
                    "\$flagged" -> flags = flags or Message.FLAGGED
                    "\$answered" -> flags = flags or Message.ANSWERED
                    "\$draft" -> flags = flags or Message.DRAFT
                    "\$deleted" -> flags = flags or Message.DELETED
                    else -> kwList.add(k)
                }
            }
            val received = runCatching {
                obj.optString("receivedAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() }
            }.getOrNull() ?: System.currentTimeMillis()
            Message(
                hex = target,
                mailbox = mailboxHex,
                threadId = obj.optString("threadId", target),
                subject = obj.optString("subject", ""),
                fromName = fromObj?.optString("name", "") ?: "",
                fromEmail = fromObj?.optString("email", "") ?: "",
                to = toStr,
                preview = obj.optString("preview", ""),
                size = obj.optLong("size", 0L),
                flags = flags,
                keywords = kwList.joinToString(" "),
                hasAttachment = obj.optBoolean("hasAttachment", false),
                receivedAt = received,
                created = System.currentTimeMillis()
            )
        }.getOrNull()

        val msg = fromJmap ?: runCatching {
            client.mailApi.search(target, limit = 1).items.firstOrNull { it.hex == target }?.let {
                HttpMailboxTransport.message(it)
            }
        }.getOrNull() ?: throw pro.aduki.core.errors.AdukiException.Api(404, "entity.missing", "Message $target not found")

        persist(msg)
        msg
    }

    private fun persist(message: Message) {
        val mgr = manager ?: return
        if (message.mailbox.isNotBlank() && mgr.mailbox(message.mailbox) == null) {
            val known = repo?.getMailbox(message.mailbox)
            if (known != null) {
                mgr.put(known)
            } else {
                val box = runCatching {
                    client.mailApi.mailboxes().items.firstOrNull { it.hex == message.mailbox }
                }.getOrNull()
                if (box != null) {
                    mgr.put(HttpMailboxTransport.mailbox(box))
                } else {
                    mgr.put(Mailbox(hex = message.mailbox, name = message.mailbox))
                }
            }
        }
        val existing = mgr.message(message.hex) ?: repo?.get(message.hex)
        if (existing != null) {
            message.id = existing.id
        }
        mgr.put(message)
    }

    /**
     * The messages of a conversation across mailboxes, oldest first.
     */
    fun thread(threadId: String): StateFlow<List<Message>>? {
        return repo?.thread(threadId)
    }

    /**
     * A mailbox grouped by conversation, newest first: per thread the latest
     * message, the message count and the unread count.
     */
    fun conversations(mailboxHex: String): StateFlow<List<Conversation>>? {
        return repo?.conversations(mailboxHex)
    }

    /**
     * Returns a hot StateFlow observing unread message count for a mailbox.
     */
    fun unread(mailboxHex: String): StateFlow<Int>? {
        return repo?.unread(mailboxHex)
    }

    private companion object {
        /** Name of the mailbox snoozed messages wait in. */
        const val SNOOZE = "Snoozed"
    }
}
