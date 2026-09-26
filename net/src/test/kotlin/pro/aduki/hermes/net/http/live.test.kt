package pro.aduki.hermes.net.http

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.hermes.core.models.MailChanges
import pro.aduki.hermes.core.models.MessageRow
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The live tier: the client against a running Hermes server, not fixtures.
 *
 * Skipped unless these are set (run with `./gradlew :net:liveTest`):
 *
 * - `HERMES_LIVE_URL`: the REST base, e.g. `https://mail.example.com/v1`
 * - `HERMES_LIVE_EMAIL`, `HERMES_LIVE_PASSWORD`: a test account (mail is
 *   sent to it and deleted again)
 *
 * It logs in, sends a message to itself, follows `/user/mail/changes` until
 * the message arrives, flags it, deletes it, and checks that an idempotent
 * retry of the send doesn't send twice.
 */
class LiveTest {

    private val url = System.getenv("HERMES_LIVE_URL").orEmpty()
    private val email = System.getenv("HERMES_LIVE_EMAIL").orEmpty()
    private val password = System.getenv("HERMES_LIVE_PASSWORD").orEmpty()

    private lateinit var mail: Mail

    @Before
    fun connect() {
        assumeTrue(
            "set HERMES_LIVE_URL, HERMES_LIVE_EMAIL and HERMES_LIVE_PASSWORD to run the live tier",
            url.isNotBlank() && email.isNotBlank() && password.isNotBlank()
        )
        val plain = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val tokens = Login.submit(plain, url, email, password)
        val http = plain.newBuilder().addInterceptor(Auth { tokens.token }).build()
        mail = Mail(http, url)
    }

    /** Drain `/changes` from [since]; returns the last page and everything created on the way. */
    private fun drain(mailbox: String, since: Long, uidvalidity: Long): Pair<MailChanges, List<MessageRow>> {
        var page = mail.changes(mailbox, since, uidvalidity)
        val created = page.created.toMutableList()
        while (page.more) {
            page = mail.changes(mailbox, page.modseq, uidvalidity)
            created += page.created
        }
        return page to created
    }

    private fun <T> eventually(what: String, seconds: Int = 30, probe: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(500)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun sendSyncFlagDelete() {
        val inbox = mail.mailboxes().items.firstOrNull { it.role == "inbox" }
        assertNotNull("the account has an inbox (role = inbox)", inbox)
        inbox!!
        var cursor = drain(inbox.hex, 0, inbox.uidvalidity).first.modseq

        val subject = "hermes-kt live ${UUID.randomUUID()}"
        val key = UUID.randomUUID().toString()
        val sent = mail.send(listOf(email), subject, "live tier", idempotencyKey = key)
        assertEquals("a retry with the same key replays the first send", sent, mail.send(listOf(email), subject, "live tier", idempotencyKey = key))

        val row = eventually("the message in the inbox") {
            val (page, created) = drain(inbox.hex, cursor, inbox.uidvalidity)
            cursor = page.modseq
            created.firstOrNull { it.subject == subject }
        }
        assertTrue(row.uid > 0)
        assertEquals(email.lowercase(), row.sender.lowercase())
        assertEquals(
            "one delivery, even with the retried send",
            1,
            mail.inbox(limit = 200).items.count { it.subject == subject }
        )

        mail.flags(row.hex, add = listOf("\\Flagged"))
        eventually("the flag change") {
            val (page, _) = drain(inbox.hex, cursor, inbox.uidvalidity)
            cursor = page.modseq
            page.updated.firstOrNull { it.uid == row.uid && "\\Flagged" in it.flags }
        }

        mail.delete(row.hex)
        eventually("the deletion") {
            val (page, _) = drain(inbox.hex, cursor, inbox.uidvalidity)
            cursor = page.modseq
            page.vanished.firstOrNull { it == row.uid }
        }
        assertEquals(0, mail.inbox(limit = 200).items.count { it.subject == subject })
    }
}
