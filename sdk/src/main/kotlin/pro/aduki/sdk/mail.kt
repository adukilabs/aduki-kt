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
            to = (draft.to + draft.cc).joinToString(", "),
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
     * Returns a hot StateFlow observing all mailboxes.
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
     * Synced messages matching [query] (subject, sender, recipients,
     * preview), newest first; only [mailbox] (a hex) when given. Offline.
     * [find] asks the server for messages not synced yet.
     */
    fun search(query: String, mailbox: String? = null): StateFlow<List<Message>>? {
        return repo?.search(query, mailbox)
    }

    /**
     * Searches on the server (`GET /user/mail/search/{q}`), including
     * mailboxes and messages not synced locally. The results are not stored.
     */
    suspend fun find(query: String, mailbox: String? = null, page: Int = 1, limit: Int = 50): List<Message> =
        withContext(Dispatchers.IO) {
            client.mailApi.search(query, mailbox, page, limit).items.map { HttpMailboxTransport.message(it) }
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
