package pro.aduki.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.aduki.core.models.MailChanges
import pro.aduki.core.models.MailboxRow
import pro.aduki.core.models.MessageRow
import pro.aduki.net.http.Mail
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Message
import pro.aduki.sync.engine.MailboxDelta
import pro.aduki.sync.engine.MailboxDirectory
import pro.aduki.sync.engine.MailboxTransport

/**
 * HttpMailboxTransport syncs a mailbox over `GET /user/mail/changes`, the
 * REST face of CONDSTORE/QRESYNC.
 *
 * A stale UIDVALIDITY (`reset`) is answered with a full fetch from MODSEQ 0,
 * which the engine applies as a fresh copy of the mailbox.
 *
 * It is also the engine's [MailboxDirectory]: `GET /user/mailbox`, every page.
 */
class HttpMailboxTransport(
    private val api: Mail,
    private val pageSize: Int = 500
) : MailboxTransport, MailboxDirectory {

    override suspend fun list(): List<Mailbox> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<MailboxRow>()
        var page = 1
        while (page <= MAX_PAGES) {
            val listing = api.mailboxes(page)
            rows += listing.items
            val pages = listing.pages ?: break
            if (page >= pages || listing.items.isEmpty()) break
            page++
        }
        rows.map(::mailbox)
    }

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
        /** Upper bound on mailbox list pages. */
        private const val MAX_PAGES = 100

        /** A mailbox list row as a local mailbox; the sync cursor starts empty. */
        fun mailbox(row: MailboxRow): Mailbox = Mailbox(
            hex = row.hex,
            name = row.name,
            role = row.role ?: "",
            uidnext = row.uidnext,
            exists = row.messages.toInt(),
            unseen = row.unread.toInt()
        )

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
