package pro.aduki.sync.outbox

import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Mailbox_
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Message_
import pro.aduki.store.entities.Outbox

/**
 * Storage abstracts outbox persistence operations for ObjectBox and test harnesses.
 */
interface Storage {
    fun getMessage(hex: String): Message?
    fun putMessage(msg: Message)
    fun getOutbox(id: Long): Outbox?
    fun putOutbox(entry: Outbox): Long
    fun removeOutbox(id: Long)
    fun pending(): List<Outbox>
    fun failed(): List<Outbox> = emptyList()
    fun <T> tx(block: () -> T): T

    /** How many actions are queued. */
    fun size(): Int = pending().size

    fun getMailbox(hex: String): Mailbox? = null
    fun putMailbox(mailbox: Mailbox) {}
    fun mailboxByRole(role: String): Mailbox? = null

    /** Drops a mailbox row and the messages in it. */
    fun removeMailbox(hex: String) {}

    /** Gives mailbox [old] the hex [new], moving its messages along. */
    fun renameMailbox(old: String, new: String) {}

    /** Gives the local message [old] the server's id [new] (after a send). */
    fun renameMessage(old: String, new: String) {
        val msg = getMessage(old) ?: return
        msg.hex = new
        putMessage(msg)
    }
}

/**
 * Manager coordinates optimistic local modifications with persistent outbox journaling.
 */
class Manager(private val storage: Storage) {

    constructor(store: BoxStore) : this(object : Storage {
        private val messages = store.boxFor(Message::class.java)
        private val outbox = store.boxFor(Outbox::class.java)

        override fun getMessage(hex: String): Message? =
            messages.query(pro.aduki.store.entities.Message_.hex.equal(hex)).build().use { it.findFirst() }
        override fun putMessage(msg: Message) { messages.put(msg) }
        override fun getOutbox(id: Long): Outbox? = outbox.get(id)?.let(Sealing::opened)
        override fun putOutbox(entry: Outbox): Long = Sealing.put(outbox, entry)
        override fun removeOutbox(id: Long) { outbox.remove(id) }
        override fun pending(): List<Outbox> =
            outbox.query(pro.aduki.store.entities.Outbox_.status.notEqual("failed"))
                .order(pro.aduki.store.entities.Outbox_.created)
                .build()
                .use { it.find().map(Sealing::opened) }
        override fun failed(): List<Outbox> =
            outbox.query(pro.aduki.store.entities.Outbox_.status.equal("failed"))
                .order(pro.aduki.store.entities.Outbox_.created)
                .build()
                .use { it.find().map(Sealing::opened) }
        override fun <T> tx(block: () -> T): T = store.callInTx(block)
        override fun size(): Int =
            outbox.query(pro.aduki.store.entities.Outbox_.status.notEqual("failed")).build().use { it.count().toInt() }

        private val mailboxes = store.boxFor(Mailbox::class.java)

        override fun getMailbox(hex: String): Mailbox? =
            mailboxes.query(Mailbox_.hex.equal(hex)).build().use { it.findFirst() }
        override fun putMailbox(mailbox: Mailbox) { mailboxes.put(mailbox) }
        override fun mailboxByRole(role: String): Mailbox? =
            mailboxes.query(Mailbox_.role.equal(role)).build().use { it.findFirst() }
        override fun removeMailbox(hex: String) {
            mailboxes.query(Mailbox_.hex.equal(hex)).build().use { it.remove() }
            messages.query(Message_.mailbox.equal(hex)).build().use { it.remove() }
        }
        override fun renameMailbox(old: String, new: String) {
            val row = getMailbox(old) ?: return
            row.hex = new
            mailboxes.put(row)
            val moved = messages.query(Message_.mailbox.equal(old)).build().use { it.find() }
            moved.forEach { it.mailbox = new }
            messages.put(moved)
        }
    })

    private val counter = MutableStateFlow(storage.size())

    /** The number of queued pending actions, updated on every change made through this manager. */
    val count: StateFlow<Int> = counter.asStateFlow()

    private val failedState = MutableStateFlow(storage.failed())

    /** The permanently failed actions, updated on every change made through this manager. */
    val failed: StateFlow<List<Outbox>> = failedState.asStateFlow()

    /** Re-reads [count] and [failed] from storage (after a write that bypassed the manager). */
    fun recount() {
        counter.value = storage.size()
        failedState.value = storage.failed()
    }

    /** The queued action with [id] as stored now, or null when it is gone. */
    fun entry(id: Long): Outbox? = storage.getOutbox(id)

    // Placeholder mailbox hexes adopted in this process, to their server hex:
    // a caller may still hold a placeholder it got from [createMailbox].
    private val adopted = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun box(hex: String): String = adopted[hex] ?: hex

    /** The local message with [hex], or null. */
    fun message(hex: String): Message? = storage.getMessage(hex)

    /** The local mailbox with [hex], or null. */
    fun mailbox(hex: String): Mailbox? = storage.getMailbox(hex)

    /** Saves [message] directly into storage. */
    fun put(message: Message) { storage.putMessage(message) }

    /** Saves [mailbox] directly into storage. */
    fun put(mailbox: Mailbox) { storage.putMailbox(mailbox) }

    /**
     * Optimistically toggles a flag bitmask on a message and journals an outbox action.
     */
    fun flag(hex: String, flag: Int): Outbox {
        return storage.tx {
            val msg = storage.getMessage(hex)
                ?: throw IllegalArgumentException("Message not found: $hex")

            msg.flags = msg.flags xor flag
            msg.dirty = true
            storage.putMessage(msg)

            // The resulting state, not the toggle: a retried or reordered
            // dispatch then converges instead of flipping the flag back.
            val payload = JSONObject()
                .put("hex", hex)
                .put("flag", flag)
                .put("set", (msg.flags and flag) != 0)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val entry = Outbox(
                hex = hex,
                action = "flag",
                payload = payload,
                created = System.currentTimeMillis()
            )
            val id = storage.putOutbox(entry)
            entry.copy(id = id)
        }.also { recount() }
    }

    /**
     * Optimistically moves a message to a destination mailbox and journals an outbox action.
     */
    fun move(hex: String, target: String): Outbox {
        val dest = box(target)
        return storage.tx {
            val msg = storage.getMessage(hex)
                ?: throw IllegalArgumentException("Message not found: $hex")

            msg.mailbox = dest
            msg.dirty = true
            storage.putMessage(msg)

            val payload = JSONObject().put("hex", hex).put("mailbox", dest).toString().toByteArray(Charsets.UTF_8)
            val entry = Outbox(
                hex = hex,
                action = "move",
                payload = payload,
                created = System.currentTimeMillis()
            )
            val id = storage.putOutbox(entry)
            entry.copy(id = id)
        }.also { recount() }
    }

    /**
     * Optimistically commits an outbound message and journals a send action.
     * [raw] is the JSON request for `POST /user/mail/send`
     * (`{to, cc, subject, text, from?}`); a non-JSON payload (0.1.x) is
     * sent as the text with the message's own recipients and subject.
     */
    fun send(msg: Message, raw: ByteArray, action: String = "send"): Outbox {
        return storage.tx {
            msg.dirty = true
            storage.putMessage(msg)

            val entry = Outbox(
                hex = msg.hex,
                action = action,
                payload = raw,
                created = System.currentTimeMillis()
            )
            val id = storage.putOutbox(entry)
            entry.copy(id = id)
        }.also { recount() }
    }

    /**
     * Optimistically removes or flags a message as deleted and journals an outbox action.
     */
    fun remove(hex: String): Outbox {
        return storage.tx {
            val msg = storage.getMessage(hex)
            if (msg != null) {
                msg.flags = msg.flags or Message.DELETED
                msg.dirty = true
                storage.putMessage(msg)
            }

            val payload = JSONObject().put("hex", hex).toString().toByteArray(Charsets.UTF_8)
            val entry = Outbox(
                hex = hex,
                action = "delete",
                payload = payload,
                created = System.currentTimeMillis()
            )
            val id = storage.putOutbox(entry)
            entry.copy(id = id)
        }.also { recount() }
    }

    /**
     * Sets ([on]) or clears [flag] on a message and journals a `flag` action.
     * Idempotent: returns null and queues nothing when the flag already is so.
     */
    fun set(hex: String, flag: Int, on: Boolean): Outbox? {
        val msg = storage.getMessage(hex) ?: throw IllegalArgumentException("Message not found: $hex")
        if (((msg.flags and flag) != 0) == on) return null
        return flag(hex, flag)
    }

    /**
     * Sets or clears [flag] on every message in [hexes] that is not so yet,
     * in one `flags` action. Unknown hexes are skipped; returns null when
     * nothing changes.
     */
    fun set(hexes: List<String>, flag: Int, on: Boolean): Outbox? {
        return storage.tx {
            val changed = hexes.distinct().mapNotNull(storage::getMessage).filter { ((it.flags and flag) != 0) != on }
            if (changed.isEmpty()) return@tx null
            for (msg in changed) {
                msg.flags = if (on) msg.flags or flag else msg.flags and flag.inv()
                msg.dirty = true
                storage.putMessage(msg)
            }
            val payload = JSONObject().put("hexes", JSONArray(changed.map { it.hex })).put("flag", flag).put("set", on)
            journal("flags", payload)
        }.also { recount() }
    }

    /**
     * Moves every message in [hexes] to [dest] in one `moves` action.
     * Unknown hexes and messages already there are skipped.
     */
    fun move(hexes: List<String>, target: String): Outbox? {
        val dest = box(target)
        return storage.tx {
            val moved = hexes.distinct().mapNotNull(storage::getMessage).filter { it.mailbox != dest }
            if (moved.isEmpty()) return@tx null
            for (msg in moved) {
                msg.mailbox = dest
                msg.dirty = true
                storage.putMessage(msg)
            }
            journal("moves", JSONObject().put("hexes", JSONArray(moved.map { it.hex })).put("mailbox", dest))
        }.also { recount() }
    }

    /**
     * Marks every message in [hexes] deleted in one `deletes` action.
     */
    fun remove(hexes: List<String>): Outbox? {
        val unique = hexes.distinct()
        if (unique.isEmpty()) return null
        return storage.tx {
            for (msg in unique.mapNotNull(storage::getMessage)) {
                msg.flags = msg.flags or Message.DELETED
                msg.dirty = true
                storage.putMessage(msg)
            }
            journal("deletes", JSONObject().put("hexes", JSONArray(unique)))
        }.also { recount() }
    }

    /**
     * Creates a mailbox locally under a placeholder hex (`Mailbox.PENDING`)
     * and journals a `mailbox_create`; [adopt] gives it the server's hex.
     */
    fun createMailbox(name: String, under: String? = null): Mailbox {
        require(name.isNotBlank()) { "Mailbox name cannot be empty" }
        val parent = under?.let(::box)
        return storage.tx {
            val box = Mailbox(hex = Mailbox.PENDING + java.util.UUID.randomUUID(), name = name)
            storage.putMailbox(box)
            val payload = JSONObject().put("hex", box.hex).put("name", name)
            if (!parent.isNullOrBlank()) payload.put("parent", parent)
            journal("mailbox_create", payload, box.hex)
            box
        }.also { recount() }
    }

    /** Renames a mailbox locally and journals a `mailbox_rename`. */
    fun renameMailbox(target: String, name: String): Outbox {
        require(name.isNotBlank()) { "Mailbox name cannot be empty" }
        val hex = box(target)
        return storage.tx {
            val box = storage.getMailbox(hex) ?: throw IllegalArgumentException("Mailbox not found: $hex")
            box.name = name
            storage.putMailbox(box)
            journal("mailbox_rename", JSONObject().put("hex", hex).put("name", name))
        }.also { recount() }
    }

    /** Drops a mailbox and its messages locally and journals a `mailbox_delete`. */
    fun deleteMailbox(target: String): Outbox {
        val hex = box(target)
        return storage.tx {
            storage.removeMailbox(hex)
            journal("mailbox_delete", JSONObject().put("hex", hex))
        }.also { recount() }
    }

    /**
     * Gives the mailbox created offline as [old] the server's hex [new]:
     * the row, its messages, and every queued action that names it.
     */
    fun adopt(old: String, new: String) {
        if (new.isBlank() || old == new) return
        adopted[old] = new
        storage.tx {
            storage.renameMailbox(old, new)
            val quoted = JSONObject.quote(old)
            for (entry in storage.pending() + storage.failed()) {
                val text = String(entry.payload, Charsets.UTF_8)
                if (quoted !in text && entry.hex != old) continue
                entry.payload = text.replace(quoted, JSONObject.quote(new)).toByteArray(Charsets.UTF_8)
                if (entry.hex == old) entry.hex = new
                storage.putOutbox(entry)
            }
        }
    }

    // Stores one action with a JSON payload; call inside a transaction.
    private fun journal(action: String, payload: JSONObject, hex: String = ""): Outbox {
        val entry = Outbox(
            hex = hex,
            action = action,
            payload = payload.toString().toByteArray(Charsets.UTF_8),
            created = System.currentTimeMillis()
        )
        return entry.copy(id = storage.putOutbox(entry))
    }

    /**
     * Manually enqueues an outbox action.
     */
    fun enqueue(action: String, payload: ByteArray, hex: String = ""): Outbox {
        val entry = Outbox(
            hex = hex,
            action = action,
            payload = payload,
            created = System.currentTimeMillis()
        )
        val id = storage.putOutbox(entry)
        recount()
        return entry.copy(id = id)
    }

    /**
     * Points every queued action on [old] at [new] (a placeholder id the
     * server replaced), so actions queued after a create reach the right row.
     */
    fun repoint(old: String, new: String) {
        if (old == new || new.isBlank()) return
        storage.tx {
            for (entry in (storage.pending() + storage.failed()).filter { it.hex == old }) {
                entry.hex = new
                storage.putOutbox(entry)
            }
        }
    }

    /** Drops every queued action on [hex]; returns how many there were. */
    fun drop(hex: String): Int {
        val gone = storage.tx {
            val entries = (storage.pending() + storage.failed()).filter { it.hex == hex }
            entries.forEach { storage.removeOutbox(it.id) }
            entries.size
        }
        recount()
        return gone
    }


    /**
     * Retrieves all pending outbox actions ordered by creation time.
     */
    fun pending(): List<Outbox> {
        return storage.pending()
    }

    /**
     * Retrieves all permanently failed outbox actions ordered by creation time.
     */
    fun failed(): List<Outbox> {
        return storage.failed()
    }

    /**
     * Marks an outbox action completed: removes from outbox and clears message dirty state.
     */
    fun complete(id: Long, hex: String? = null) {
        complete(id, listOfNotNull(hex))
    }

    /**
     * Marks an outbox action as permanently failed with an error code and reason.
     */
    fun reject(id: Long, code: Int?, reason: String, hexes: List<String> = emptyList()) {
        reject(id, code?.toString() ?: "", reason, hexes)
    }

    /**
     * Marks an outbox action as permanently failed with an error code and reason.
     */
    fun reject(id: Long, code: String = "", reason: String = "", hexes: List<String> = emptyList()) {
        storage.tx {
            val entry = storage.getOutbox(id) ?: return@tx
            entry.status = "failed"
            entry.code = code
            entry.reason = reason
            storage.putOutbox(entry)
            for (hex in hexes) {
                val msg = storage.getMessage(hex) ?: continue
                msg.dirty = false
                storage.putMessage(msg)
            }
        }
        recount()
    }

    /**
     * Retries a permanently failed outbox action by restoring its status to pending.
     */
    fun retry(id: Long): Boolean {
        val ok = storage.tx {
            val entry = storage.getOutbox(id) ?: return@tx false
            entry.status = "pending"
            entry.attempts = 0
            entry.nextRetry = 0L
            entry.code = ""
            entry.reason = ""
            storage.putOutbox(entry)
            if (entry.hex.isNotBlank()) {
                val msg = storage.getMessage(entry.hex)
                if (msg != null) {
                    msg.dirty = true
                    storage.putMessage(msg)
                }
            }
            true
        }
        if (ok) recount()
        return ok
    }

    /**
     * Discards an outbox action: removes it from storage. If it was a send action,
     * moves the message to drafts so it is preserved locally.
     */
    fun discard(id: Long): Boolean {
        val ok = storage.tx {
            val entry = storage.getOutbox(id) ?: return@tx false
            if (entry.action == "send" || entry.action == "compose") {
                val hex = entry.hex.ifBlank { extractHex(entry) }
                if (!hex.isNullOrBlank()) {
                    val msg = storage.getMessage(hex)
                    if (msg != null) {
                        val drafts = storage.mailboxByRole("drafts")?.hex ?: "drafts"
                        msg.mailbox = drafts
                        msg.flags = (msg.flags or Message.DRAFT) and Message.DELETED.inv()
                        msg.dirty = false
                        storage.putMessage(msg)
                    }
                }
            } else if (entry.hex.isNotBlank()) {
                val msg = storage.getMessage(entry.hex)
                if (msg != null) {
                    msg.dirty = false
                    storage.putMessage(msg)
                }
            }
            storage.removeOutbox(id)
            true
        }
        if (ok) recount()
        return ok
    }

    private fun extractHex(entry: Outbox): String? {
        return try {
            val str = String(entry.payload, Charsets.UTF_8)
            if (str.trimStart().startsWith("{")) {
                org.json.JSONObject(str).optString("hex").ifBlank { null }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Adds and removes keywords (non-system flags such as `$Junk`) on a
     * message and journals a `keywords` action.
     */
    fun keywords(hex: String, add: List<String> = emptyList(), remove: List<String> = emptyList()): Outbox {
        return storage.tx {
            val msg = storage.getMessage(hex) ?: throw IllegalArgumentException("Message not found: $hex")
            msg.keywords = ((msg.keywordList() - remove.toSet()) + add).distinct().joinToString(" ")
            msg.dirty = true
            storage.putMessage(msg)
            journal("keywords", JSONObject().put("hex", hex).put("add", JSONArray(add)).put("remove", JSONArray(remove)))
        }.also { recount() }
    }

    /** Like `complete(id, hex)` for an action over several messages. */
    fun complete(id: Long, hexes: List<String>) {
        storage.tx {
            storage.removeOutbox(id)
            for (hex in hexes) {
                val msg = storage.getMessage(hex) ?: continue
                msg.dirty = false
                storage.putMessage(msg)
            }
        }
        recount()
    }

    /**
     * Replaces a sent message's local placeholder id with the server's. It
     * is called only after the send succeeded, so the message is no longer
     * dirty (completion clears by the old id, which no longer exists).
     */
    fun rename(old: String, new: String) {
        if (new.isBlank()) return
        storage.tx {
            if (old != new) {
                storage.renameMessage(old, new)
            }
            val msg = storage.getMessage(new) ?: return@tx
            msg.dirty = false
            storage.putMessage(msg)
        }
    }

    /**
     * Records a failed dispatch attempt, scheduling the next retry.
     */
    fun fail(id: Long, delay: Long) {
        storage.tx {
            val entry = storage.getOutbox(id) ?: return@tx
            entry.attempts += 1
            entry.nextRetry = System.currentTimeMillis() + delay
            storage.putOutbox(entry)
        }
    }
}

