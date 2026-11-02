package pro.aduki.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.aduki.core.errors.Faults
import pro.aduki.core.models.Body
import pro.aduki.core.models.BodyMode
import pro.aduki.core.models.Confidence
import pro.aduki.core.models.Conversation
import pro.aduki.core.models.Person
import pro.aduki.core.thread.Entry
import pro.aduki.core.thread.Threads
import pro.aduki.store.entities.Message
import pro.aduki.sync.http.JmapContentTransport
import pro.aduki.sync.http.JmapThreadTransport
import java.time.Instant
import pro.aduki.state.repository.Mail as MailRepo
import pro.aduki.sync.engine.Content as ContentEngine

/**
 * Conversations loads a thread for `Mail.conversation`: the emails from the
 * server (or the local store offline), the server's reading where it has
 * one, bodies only for messages without a usable reply, then [Threads].
 *
 * What the server read about a message (headers, `aduki:reply`,
 * `aduki:security`) does not change, so it is kept in memory by hex; only
 * keywords and mailboxes are read again.
 */
internal class Conversations(
    private val client: Aduki,
    private val repo: MailRepo?,
    private val content: ContentEngine?
) {
    private val threads by lazy { JmapThreadTransport(client.jmap) }

    // Entries read from the server, by hex: the reading and headers, kept across loads.
    private val known = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CACHE
    }

    /**
     * The conversation [threadId]: from the server, or from the local store
     * when the server cannot be reached and the store has the thread.
     */
    suspend fun load(threadId: String, refresh: Boolean): Conversation = client.io {
        require(threadId.isNotBlank()) { "thread id is required" }
        val remote = try {
            remote(threadId, refresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val local = local(threadId)
            if (local.isEmpty()) throw e
            Faults.report("conversation fetch", e)
            return@io build(threadId, offline(local), offline = true)
        }
        build(threadId, remote, offline = false)
    }

    /** The conversation from the local store alone, or null when the store does not have the thread. */
    suspend fun cached(threadId: String): Conversation? = client.io {
        val local = local(threadId)
        if (local.isEmpty()) return@io null
        build(threadId, offline(local), offline = true)
    }

    /** A key that changes when the local thread does (messages, flags, mailboxes). */
    fun version(rows: List<Message>): List<String> =
        rows.filterNot { it.deleted() }.map { "${it.hex}:${it.flags}:${it.mailbox}:${it.keywords}" }.sorted()

    private suspend fun build(threadId: String, entries: List<Entry>, offline: Boolean): Conversation =
        withContext(Dispatchers.Default) {
            Threads.build(threadId, entries).copy(offline = offline)
        }

    private suspend fun remote(threadId: String, refresh: Boolean): List<Entry> {
        val fetched = threads.emails(threadId)
        val local = repo?.let { r -> fetched.associate { it.hex to r.get(it.hex) } }.orEmpty()
        // Messages deleted on this device and not expunged yet stay hidden.
        val emails = fetched.filterNot { local[it.hex]?.deleted() == true }
        val extras = try {
            threads.extras()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Faults.report("reading capability", e)
            emptySet()
        }
        val unread = synchronized(known) { emails.filter { refresh || known[it.hex] == null }.map { it.hex } }
        var read = true
        val readings = if (extras.isEmpty() || unread.isEmpty()) emptyMap() else try {
            threads.readings(unread, extras)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A server that refuses the properties: the client reads the bodies itself, and asks again next time.
            Faults.report("thread reading", e)
            read = false
            emptyMap()
        }
        val merged = emails.map { email ->
            val reading = readings[email.hex]
            val cached = synchronized(known) { known[email.hex] }
            val full = when {
                reading != null -> email.copy(reply = reading.reply, security = reading.security)
                cached != null -> email.copy(reply = cached.reply, security = cached.security)
                else -> email
            }
            if (read) synchronized(known) { known[email.hex] = full.copy(keywords = emptySet()) }
            overlay(full, local[email.hex])
        }
        return withBodies(merged, online = true)
    }

    // The thread in the local store, without deleted messages.
    private fun local(threadId: String): List<Message> =
        repo?.listThread(threadId)?.filterNot { it.deleted() }.orEmpty()

    private fun offline(rows: List<Message>): List<Entry> {
        val sent = repo?.listMailboxes()?.filter { it.role == "sent" }?.map { it.hex }?.toSet().orEmpty()
        return rows.map { offline(it, sent) }
    }

    // A stored message as an entry: what the server told earlier when it is remembered, else the stored row.
    private fun offline(row: Message, sentBoxes: Set<String>): Entry {
        val remembered = synchronized(known) { known[row.hex] }
        val entry = remembered?.copy(mailboxes = setOf(row.mailbox), sent = row.mailbox in sentBoxes) ?: Entry(
            hex = row.hex,
            messageId = row.messageId.ifBlank { null },
            subject = row.subject,
            from = Person(name = row.fromName.ifBlank { null }, email = row.fromEmail),
            to = people(row.to),
            cc = people(row.cc),
            replyTo = people(row.replyTo),
            sentAt = row.sentAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            receivedAt = row.receivedAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            mailboxes = setOfNotNull(row.mailbox.ifBlank { null }),
            sent = row.mailbox in sentBoxes
        )
        return overlay(entry, row).let { e ->
            if (usable(e)) e else {
                val body = try {
                    content?.cached(e.hex)
                } catch (x: Exception) {
                    Faults.report("conversation body cache", x)
                    null
                }
                if (body != null) withBody(e, body) else e.copy(text = e.text.ifEmpty { row.preview })
            }
        }
    }

    // Keywords as this device has them: local flag changes are not synced yet.
    private fun overlay(entry: Entry, row: Message?): Entry {
        if (row == null) return entry
        val keywords = entry.keywords.filterNot { k -> FLAGS.any { it.equals(k, ignoreCase = true) } }.toMutableSet()
        if (row.seen()) keywords += "\$seen"
        if (row.flagged()) keywords += "\$flagged"
        if (row.flags and Message.ANSWERED != 0) keywords += "\$answered"
        if (row.flags and Message.DRAFT != 0) keywords += "\$draft"
        keywords += row.keywordList()
        return entry.copy(keywords = keywords)
    }

    // A server reply the conversation can show as it is.
    private fun usable(entry: Entry): Boolean = entry.reply?.let { it.confidence != Confidence.Low } == true

    // A low-confidence server reply already carries the whole text: no body needed.
    private fun needs(entry: Entry): Boolean = entry.reply == null && entry.text.isEmpty() && entry.html.isEmpty()

    private suspend fun withBodies(entries: List<Entry>, online: Boolean): List<Entry> {
        val wanted = entries.filter(::needs).map { it.hex }
        if (wanted.isEmpty()) return entries
        val bodies: Map<String, Body> = try {
            val engine = content
            when {
                engine != null -> engine.bodies(wanted)
                online -> JmapContentTransport(client.jmap).fetchAll(wanted, BodyMode.Both).associateBy { it.hex }
                else -> emptyMap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Faults.report("conversation bodies", e)
            emptyMap()
        }
        return entries.map { e -> bodies[e.hex]?.let { withBody(e, it) } ?: e }
    }

    private fun withBody(entry: Entry, body: Body): Entry = entry.copy(
        text = body.text,
        html = body.html,
        quoteStart = body.quoteStart,
        attachments = entry.attachments.ifEmpty { body.attachments }
    )

    private fun people(list: String): List<Person> =
        list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Person(email = it) }

    private companion object {
        /** Messages whose server reading is kept in memory. */
        const val CACHE = 2_000

        val FLAGS = listOf("\$seen", "\$flagged", "\$answered", "\$draft")
    }
}
