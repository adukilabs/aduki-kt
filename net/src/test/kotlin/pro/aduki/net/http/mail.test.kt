package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException

/**
 * Contract tests: the client against the server's own response shapes
 * (`fixtures/`, copied from the Aduki repo's `guide/fixtures/sdk`).
 */
class MailTest {

    private lateinit var server: MockWebServer
    private lateinit var mail: Mail

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    private fun reply(name: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture(name)))
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mail = Mail(OkHttpClient(), server.url("/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun changesParsesCreatedUpdatedVanished() {
        reply("changes.json")
        val c = mail.changes("M0X1A2B3C4D5E6F", since = 11500, uidvalidity = 1790413341)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/user/mail/changes", req.requestUrl!!.encodedPath)
        assertEquals("M0X1A2B3C4D5E6F", req.requestUrl!!.queryParameter("mailbox"))
        assertEquals("11500", req.requestUrl!!.queryParameter("since"))
        assertEquals("1790413341", req.requestUrl!!.queryParameter("uidvalidity"))

        assertEquals(1790413341L, c.uidvalidity)
        assertEquals(11556L, c.modseq)
        assertFalse(c.reset)
        assertFalse(c.more)
        val row = c.created.single()
        assertEquals("M0GDB6EC798DDC2F3460", row.hex)
        assertEquals(4L, row.uid)
        assertEquals("Bob Example", row.from!!.name)
        assertEquals("bob@example.com", row.from!!.email)
        assertEquals("alice@aduki.test", row.to.single().email)
        assertTrue(row.hasAttachment)
        assertEquals("M0X1A2B3C4D5E6F", row.mailbox)
        assertEquals("INBOX", row.mailboxName)
        assertEquals(11556L, row.modseq)
        assertNull(row.spam)
        assertEquals(1790413340658L, row.received)
        val up = c.updated.single()
        assertEquals(listOf("\\Seen", "\\Flagged"), up.flags)
        assertEquals(listOf(3L), c.vanished)
    }

    @Test
    fun firstSyncOmitsUidvalidityAndResetIsReported() {
        reply("changes_reset.json")
        val c = mail.changes("M0X1A2B3C4D5E6F", since = 0)
        assertNull(server.takeRequest().requestUrl!!.queryParameter("uidvalidity"))
        assertTrue(c.reset)
        assertTrue(c.created.isEmpty() && c.updated.isEmpty() && c.vanished.isEmpty())
    }

    @Test
    fun inboxParsesAListingAndSendsTheCursor() {
        reply("inbox.json")
        val page = mail.inbox(after = "M0GPREVIOUS", limit = 25)
        val req = server.takeRequest()
        assertEquals("/v1/user/mail/inbox", req.requestUrl!!.encodedPath)
        assertEquals("M0GPREVIOUS", req.requestUrl!!.queryParameter("after"))
        assertEquals("25", req.requestUrl!!.queryParameter("limit"))
        assertEquals(1L, page.total)
        assertEquals("M0GDB6EC798DDC2F3460", page.next)
        val row = page.items.single()
        assertNull(row.from!!.name)
        assertEquals(0.02, row.spam!!, 1e-9)
        assertEquals(listOf("\\Seen"), row.flags)
    }

    @Test
    fun mailboxesParse() {
        reply("mailboxes.json")
        val page = mail.mailboxes()
        assertEquals(listOf("INBOX", "Archive"), page.items.map { it.name })
        assertEquals(1, page.pages)
        assertEquals(listOf("\\Archive"), page.items[1].flags)
        assertEquals(1L, page.items[0].unread)
        assertEquals(listOf("inbox", "archive"), page.items.map { it.role })
    }

    @Test
    fun sendPostsRecipientsAndTheIdempotencyKey() {
        reply("send.json")
        val hex = mail.send(
            to = listOf("bob@example.com", "carol@example.com"),
            subject = "Hi \"there\"",
            text = "Hello",
            idempotencyKey = "outbox-7-1700000000000"
        )
        assertEquals("M0GAA11BB22CC33DD44", hex)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/user/mail/send", req.requestUrl!!.encodedPath)
        assertEquals("outbox-7-1700000000000", req.getHeader("Idempotency-Key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(2, body.getJSONArray("to").length())
        assertEquals("Hi \"there\"", body.getString("subject"))
        assertFalse("from defaults server-side", body.has("from"))
    }

    @Test
    fun moveAndFlagsUseTheRightVerbsAndBodies() {
        reply("move.json")
        val moved = mail.move("M0GDB6EC798DDC2F3460", "M0X6F5E4D3C2B1A")
        assertEquals(1L, moved.uid)
        val mv = server.takeRequest()
        assertEquals("PATCH", mv.method)
        assertEquals("/v1/user/mail/M0GDB6EC798DDC2F3460/mailbox", mv.requestUrl!!.encodedPath)
        assertEquals("M0X6F5E4D3C2B1A", JSONObject(mv.body.readUtf8()).getString("mailbox"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("null"))
        mail.flags("M0GDB6EC798DDC2F3460", add = listOf("\\Seen"), remove = listOf("\\Flagged"))
        val fl = server.takeRequest()
        assertEquals("PATCH", fl.method)
        assertEquals("/v1/user/mail/M0GDB6EC798DDC2F3460/flags", fl.requestUrl!!.encodedPath)
        val body = JSONObject(fl.body.readUtf8())
        assertEquals("\\Seen", body.getJSONArray("add").getString(0))
        assertEquals("\\Flagged", body.getJSONArray("remove").getString(0))

        server.enqueue(MockResponse().setResponseCode(200).setBody("null"))
        mail.delete("M0GDB6EC798DDC2F3460")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun errorsMapToAdukiExceptions() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            mail.inbox()
            fail("expected Auth")
        } catch (_: AdukiException.Auth) {
        }
        server.enqueue(MockResponse().setResponseCode(409))
        try {
            mail.send(listOf("a@b.c"), "s", "t", idempotencyKey = "k")
            fail("expected Network")
        } catch (e: AdukiException.Network) {
            assertEquals(409, e.code)
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        try {
            mail.changes("M", 0)
            fail("expected Protocol")
        } catch (_: AdukiException.Protocol) {
        }
    }

    @Test
    fun timestampsAreUtc() {
        assertEquals(0L, Mail.timestamp(""))
        assertEquals(1790413340000L, Mail.timestamp("2026-09-26T09:02:20"))
        assertEquals(1790413340000L, Mail.timestamp("2026-09-26T09:02:20Z"))
        assertEquals(1790406140000L, Mail.timestamp("2026-09-26T09:02:20+02:00"))
    }
}
