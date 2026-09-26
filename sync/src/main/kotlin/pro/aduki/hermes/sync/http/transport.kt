package pro.aduki.hermes.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.aduki.hermes.core.models.MailChanges
import pro.aduki.hermes.core.models.MessageRow
import pro.aduki.hermes.net.http.Mail
import pro.aduki.hermes.store.entities.Message
import pro.aduki.hermes.sync.engine.MailboxDelta
import pro.aduki.hermes.sync.engine.MailboxTransport

/**
 * HttpMailboxTransport syncs a mailbox over `GET /user/mail/changes`, the
 * REST face of CONDSTORE/QRESYNC.
 *
 * A stale UIDVALIDITY (`reset`) is answered with a full fetch from MODSEQ 0,
 * which the engine applies as a fresh copy of the mailbox.
 */
class HttpMailboxTransport(
    private val api: Mail,
    private val pageSize: Int = 500
) : MailboxTransport {

    override suspend fun fetch(mailbox: String, uidvalidity: Long, modseq: Long): MailboxDelta =
        withContext(Dispatchers.IO) {
            // uidvalidity 0 = never synced: ask from the beginning.
            val since = if (uidvalidity > 0) modseq else 0L
            var changes = api.changes(mailbox, since, uidvalidity.takeIf { it > 0 }, pageSize)
            if (changes.reset) {
                changes = api.changes(mailbox, 0L, null, pageSize)
            }
            delta(mailbox, changes)
        }

    companion object {
        /** Maps a changes response to the engine's delta. */
        fun delta(mailbox: String, changes: MailChanges): MailboxDelta {
            val created = changes.created.map { message(it, mailbox) }
            val updated = changes.updated.map { u ->
                Message(
                    hex = u.hex,
                    uid = u.uid,
                    mailbox = mailbox,
                    flags = Message.bits(u.flags),
                    keywords = Message.keywords(u.flags),
                    modseq = u.modseq
                )
            }
            return MailboxDelta(
                newUids = created.map { it.uid },
                changedUids = updated.map { it.uid },
                removedUids = changes.vanished,
                modseq = changes.modseq,
                uidvalidity = changes.uidvalidity,
                messages = created + updated,
                more = changes.more
            )
        }

        /** A list row as a local message. */
        fun message(row: MessageRow, mailbox: String = row.mailbox): Message = Message(
            hex = row.hex,
            mailbox = mailbox,
            uid = row.uid,
            threadId = row.thread ?: row.hex,
            subject = row.subject,
            fromName = row.from?.name ?: "",
            fromEmail = row.from?.email ?: row.sender,
            to = row.to.joinToString(", ") { it.email },
            preview = row.preview,
            size = row.size,
            flags = Message.bits(row.flags),
            keywords = Message.keywords(row.flags),
            hasAttachment = row.hasAttachment,
            receivedAt = row.received,
            modseq = row.modseq,
            created = System.currentTimeMillis()
        )
    }
}
