package pro.aduki.hermes.sdk

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.hermes.store.entities.Mailbox
import pro.aduki.hermes.store.entities.Message
import pro.aduki.hermes.sync.outbox.Manager
import pro.aduki.hermes.sync.outbox.Worker
import pro.aduki.hermes.state.repository.Mail as MailRepo

/**
 * Mail service providing high-level operations for mailboxes and messages.
 */
class Mail internal constructor(
    private val client: HermesClient,
    private val manager: Manager? = null,
    private val repo: MailRepo? = null,
    private val worker: Worker? = null
) {

    /**
     * Sends an email message: writes optimistically to local store, journals outbox action, and flushes.
     */
    suspend fun send(to: List<String>, subject: String, body: String, mailbox: String = "outbox"): Message {
        val hex = "msg_${System.currentTimeMillis()}"
        val msg = Message(
            hex = hex,
            mailbox = mailbox,
            to = to.joinToString(", "),
            subject = subject,
            preview = body.take(120),
            created = System.currentTimeMillis(),
            dirty = true
        )
        val raw = JSONObject()
            .put("to", JSONArray(to))
            .put("subject", subject)
            .put("text", body)
            .toString()
            .toByteArray(Charsets.UTF_8)
        manager?.send(msg, raw)
        worker?.drain()
        return msg
    }

    /**
     * Toggles a flag bitmask optimistically on a message.
     */
    suspend fun flag(hex: String, flag: Int) {
        manager?.flag(hex, flag)
    }

    /**
     * Moves a message optimistically to a destination mailbox.
     */
    suspend fun move(hex: String, dest: String) {
        manager?.move(hex, dest)
    }

    /**
     * Removes or marks a message as deleted optimistically.
     */
    suspend fun remove(hex: String) {
        manager?.remove(hex)
    }

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
     * Returns a hot StateFlow observing unread message count for a mailbox.
     */
    fun unread(mailboxHex: String): StateFlow<Int>? {
        return repo?.unread(mailboxHex)
    }
}
