package pro.aduki.sdk

import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.sync.engine.Mailbox as MailboxEngine
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Worker

/**
 * Sync service coordinating CONDSTORE/MODSEQ mail sync and address book delta sync.
 */
class Sync internal constructor(
    private val client: Aduki,
    private val mailboxEngine: MailboxEngine? = null,
    private val contactEngine: ContactEngine? = null,
    private val worker: Worker? = null,
    private val manager: Manager? = null
) {

    /**
     * Runs incremental delta sync across specified mailboxes and address book.
     */
    suspend fun all(mailboxes: List<String> = listOf("inbox")): Boolean {
        var success = true
        for (box in mailboxes) {
            val ok = mailboxEngine?.sync(box) ?: true
            if (!ok) success = false
        }
        val tenant = client.me()?.tenant ?: ""
        if (tenant.isNotBlank()) {
            contactEngine?.sync(tenant)
        }
        return success
    }

    /**
     * Flushes all pending outbox mutations over the network.
     */
    suspend fun flush(): Int {
        return worker?.drain() ?: 0
    }

    /**
     * Returns the count of pending outbox mutations.
     */
    fun pending(): Int {
        return manager?.pending()?.size ?: 0
    }
}
