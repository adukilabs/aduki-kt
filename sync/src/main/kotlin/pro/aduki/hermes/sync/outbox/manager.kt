package pro.aduki.hermes.sync.outbox

import io.objectbox.BoxStore
import org.json.JSONObject
import pro.aduki.hermes.store.entities.Message
import pro.aduki.hermes.store.entities.Outbox

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
    fun <T> tx(block: () -> T): T

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
            messages.query(pro.aduki.hermes.store.entities.Message_.hex.equal(hex)).build().findFirst()
        override fun putMessage(msg: Message) { messages.put(msg) }
        override fun getOutbox(id: Long): Outbox? = outbox.get(id)
        override fun putOutbox(entry: Outbox): Long = outbox.put(entry)
        override fun removeOutbox(id: Long) { outbox.remove(id) }
        override fun pending(): List<Outbox> = outbox.all.sortedBy { it.created }
        override fun <T> tx(block: () -> T): T = store.callInTx(block)
    })

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
        }
    }

    /**
     * Optimistically moves a message to a destination mailbox and journals an outbox action.
     */
    fun move(hex: String, dest: String): Outbox {
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
        }
    }

    /**
     * Optimistically commits an outbound message and journals a send action.
     * [raw] is the JSON request for `POST /user/mail/send`
     * (`{to, cc, subject, text, from?}`); a non-JSON payload (0.1.x) is
     * sent as the text with the message's own recipients and subject.
     */
    fun send(msg: Message, raw: ByteArray): Outbox {
        return storage.tx {
            msg.dirty = true
            storage.putMessage(msg)

            val entry = Outbox(
                hex = msg.hex,
                action = "send",
                payload = raw,
                created = System.currentTimeMillis()
            )
            val id = storage.putOutbox(entry)
            entry.copy(id = id)
        }
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
        }
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
        return entry.copy(id = id)
    }

    /**
     * Retrieves all pending outbox actions ordered by creation time.
     */
    fun pending(): List<Outbox> {
        return storage.pending()
    }

    /**
     * Marks an outbox action completed: removes from outbox and clears message dirty state.
     */
    fun complete(id: Long, hex: String? = null) {
        storage.tx {
            storage.removeOutbox(id)
            if (hex != null) {
                val msg = storage.getMessage(hex)
                if (msg != null) {
                    msg.dirty = false
                    storage.putMessage(msg)
                }
            }
        }
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

