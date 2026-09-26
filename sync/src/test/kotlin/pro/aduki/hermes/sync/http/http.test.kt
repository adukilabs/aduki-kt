package pro.aduki.hermes.sync.http

import kotlinx.coroutines.runBlocking
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
import pro.aduki.hermes.core.errors.HermesException
import pro.aduki.hermes.net.http.Mail
import pro.aduki.hermes.store.entities.Mailbox as MailboxEntity
import pro.aduki.hermes.store.entities.Message
import pro.aduki.hermes.store.entities.Outbox
import pro.aduki.hermes.sync.engine.Mailbox
import pro.aduki.hermes.sync.engine.MailboxStorage
import pro.aduki.hermes.sync.outbox.Dispatcher
import pro.aduki.hermes.sync.outbox.Manager
import pro.aduki.hermes.sync.outbox.Rejected
import pro.aduki.hermes.sync.outbox.Storage
import pro.aduki.hermes.sync.outbox.Worker

/**
 * The sync transport and outbox dispatcher against the server's response
 * shapes (fixtures copied from the Hermes repo's `guide/fixtures/sdk`).
 */
class HttpTest {

    private lateinit var server: MockWebServer
    private lateinit var api: Mail

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Mail(OkHttpClient(), server.url("/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class Boxes : MailboxStorage {
        val mailboxes = mutableMapOf<String, MailboxEntity>()
        val messages = mutableMapOf<Long, Message>()
        private var next = 100L

        override fun getMailbox(hex: String) = mailboxes[hex]
        override fun putMailbox(mailbox: MailboxEntity) { mailboxes[mailbox.hex] = mailbox }
        override fun getMessages(mailboxHex: String) = messages.values.filter { it.mailbox == mailboxHex }
        override fun putMessages(messages: List<Message>) {
            messages.forEach {
                val m = if (it.id == 0L) it.copy(id = next++) else it
                this.messages[m.id] = m
            }
        }
        override fun removeMessages(messages: List<Message>) { messages.forEach { this.messages.remove(it.id) } }
        override fun clearMailbox(mailboxHex: String) { messages.values.removeIf { it.mailbox == mailboxHex } }
        override fun <T> tx(block: () -> T): T = block()
        override fun findByHex(hexes: List<String>) = messages.values.filter { it.hex in hexes }
    }

    @Test
    fun changesBecomeADelta() {
        val delta = HttpMailboxTransport.delta("M0X1A2B3C4D5E6F", Mail.parseChanges(JSONObject(fixture("changes.json"))))
        assertEquals(listOf(4L), delta.newUids)
        assertEquals(listOf(2L), delta.changedUids)
        assertEquals(listOf(3L), delta.removedUids)
        assertEquals(11556L, delta.modseq)
        val created = delta.messages.first { it.uid == 4L }
        assertEquals("Bob Example", created.fromName)
        assertEquals("bob@example.com", created.fromEmail)
        assertEquals("T0X9F8E7D6C5B4A", created.threadId)
        assertTrue(created.hasAttachment)
        assertEquals(1790413340658L, created.receivedAt)
        val updated = delta.messages.first { it.uid == 2L }
        assertEquals(Message.SEEN or Message.FLAGGED, updated.flags)
    }

    @Test
    fun firstSyncThenPagesThenAMoveIn() = runBlocking {
        val boxes = Boxes()
        boxes.putMailbox(MailboxEntity(hex = "INBOX1", name = "INBOX"))
        // Local copy of a message that another device moves into INBOX1.
        boxes.putMessages(listOf(Message(id = 7L, hex = "moved", mailbox = "ARCHIVE", uid = 9L)))
        val row = { hex: String, uid: Long, seq: Long ->
            """{"hex":"$hex","uid":$uid,"subject":"s$uid","sender":null,"size":1,"flags":[],"thread":null,
               "spam":null,"internaldate":"2026-09-26T09:02:20","preview":null,"from":null,"to":[],
               "has_attachment":false,"mailbox":{"hex":"INBOX1","name":"INBOX"},"total":0,"modseq":$seq}"""
        }
        // Page 1 (first sync, since 0): two messages, more to come.
        reply("""{"mailbox":"INBOX1","uidvalidity":42,"modseq":11,"reset":false,"more":true,
                 "created":[${row("a", 1, 10)},${row("b", 2, 11)}],"updated":[],"vanished":[]}""")
        // Page 2: one more message, and a move-in keeping its local row.
        reply("""{"mailbox":"INBOX1","uidvalidity":42,"modseq":15,"reset":false,"more":false,
                 "created":[${row("c", 3, 12)},${row("moved", 4, 15)}],"updated":[],"vanished":[]}""")

        val engine = Mailbox(boxes, HttpMailboxTransport(api))
        assertTrue(engine.sync("INBOX1"))

        val first = server.takeRequest().requestUrl!!
        assertEquals("0", first.queryParameter("since"))
        assertNull(first.queryParameter("uidvalidity"))
        val second = server.takeRequest().requestUrl!!
        assertEquals("11", second.queryParameter("since"))
        assertEquals("42", second.queryParameter("uidvalidity"))

        val inbox = boxes.getMessages("INBOX1").sortedBy { it.uid }
        assertEquals(listOf("a", "b", "c", "moved"), inbox.map { it.hex })
        assertEquals(7L, inbox.last().id)
        assertEquals(1, boxes.messages.values.count { it.hex == "moved" })
        assertEquals(15L, boxes.getMailbox("INBOX1")!!.modseq)
        assertEquals(42L, boxes.getMailbox("INBOX1")!!.uidvalidity)
    }

    @Test
    fun aStaleUidvalidityRefetchesFromZero() = runBlocking {
        reply(fixture("changes_reset.json"))
        reply("""{"mailbox":"M0X1A2B3C4D5E6F","uidvalidity":1790499999,"modseq":11600,"reset":false,"more":false,
                 "created":[],"updated":[],"vanished":[]}""")
        val delta = HttpMailboxTransport(api).fetch("M0X1A2B3C4D5E6F", 1790413341, 11500)
        assertEquals("11500", server.takeRequest().requestUrl!!.queryParameter("since"))
        val again = server.takeRequest().requestUrl!!
        assertEquals("0", again.queryParameter("since"))
        assertNull(again.queryParameter("uidvalidity"))
        assertEquals(1790499999L, delta.uidvalidity)
    }

    private class Journal : Storage {
        val messages = mutableMapOf<String, Message>()
        val outbox = mutableMapOf<Long, Outbox>()
        private var next = 1L
        override fun getMessage(hex: String) = messages[hex]
        override fun putMessage(msg: Message) { messages[msg.hex] = msg }
        override fun getOutbox(id: Long) = outbox[id]
        override fun putOutbox(entry: Outbox): Long {
            val id = if (entry.id == 0L) next++ else entry.id
            outbox[id] = entry.copy(id = id)
            return id
        }
        override fun removeOutbox(id: Long) { outbox.remove(id) }
        override fun pending() = outbox.values.sortedBy { it.created }
        override fun <T> tx(block: () -> T): T = block()
        override fun renameMessage(old: String, new: String) {
            val m = messages.remove(old) ?: return
            m.hex = new
            messages[new] = m
        }
    }

    @Test
    fun sendUsesAStableIdempotencyKeyAndAdoptsTheServerId() = runBlocking {
        val journal = Journal()
        val manager = Manager(journal)
        val placeholder = Message(hex = "msg_1", mailbox = "outbox", to = "bob@example.com", subject = "Hi")
        val entry = manager.send(
            placeholder,
            """{"to":["bob@example.com"],"subject":"Hi","text":"Hello"}""".toByteArray()
        )
        val dispatcher = HttpDispatcher(api, journal::getMessage, manager::rename)

        // First attempt: the response is lost (server error) — retried later with the same key.
        reply("boom", 500)
        val worker = Worker(manager, dispatcher)
        assertEquals(0, worker.drain())
        journal.outbox[entry.id]!!.nextRetry = 0
        reply(fixture("send.json"))
        assertEquals(1, worker.drain())

        val keys = (1..2).map { server.takeRequest().getHeader("Idempotency-Key") }
        assertEquals(HttpDispatcher.idempotencyKey(entry), keys[0])
        assertEquals(keys[0], keys[1])
        assertTrue(journal.outbox.isEmpty())
        val sent = journal.messages["M0GAA11BB22CC33DD44"]!!
        assertFalse(sent.dirty)
        assertNull(journal.messages["msg_1"])
    }

    @Test
    fun flagMoveAndDeleteSpeakTheRestApi() = runBlocking {
        val journal = Journal()
        journal.putMessage(Message(hex = "m1", mailbox = "INBOX1", flags = 0))
        val manager = Manager(journal)
        val dispatcher = HttpDispatcher(api, journal::getMessage)

        manager.flag("m1", Message.SEEN)
        manager.flag("m1", Message.FLAGGED)
        manager.flag("m1", Message.SEEN) // back off: must end as a remove
        manager.move("m1", "ARCHIVE1")
        manager.remove("m1")
        repeat(3) { reply("null") }
        reply(fixture("move.json"))
        reply("", 404) // already gone on the server: nothing to do
        assertEquals(5, Worker(manager, dispatcher).drain())

        val calls = (1..5).map { server.takeRequest() }
        assertEquals(listOf("PATCH", "PATCH", "PATCH", "PATCH", "DELETE"), calls.map { it.method })
        val bodies = calls.take(3).map { JSONObject(it.body.readUtf8()) }
        assertEquals("\\Seen", bodies[0].getJSONArray("add").getString(0))
        assertEquals("\\Flagged", bodies[1].getJSONArray("add").getString(0))
        assertEquals("\\Seen", bodies[2].getJSONArray("remove").getString(0))
        assertEquals("/v1/user/mail/m1/mailbox", calls[3].requestUrl!!.encodedPath)
        assertEquals("/v1/user/mail/m1", calls[4].requestUrl!!.encodedPath)
    }

    @Test
    fun legacyPayloadsStillDispatch() = runBlocking {
        val journal = Journal()
        journal.putMessage(Message(hex = "m1", flags = Message.FLAGGED, to = "a@x.test", subject = "old"))
        val dispatcher = HttpDispatcher(api, journal::getMessage)
        reply("null")
        dispatcher.dispatch(Outbox(id = 1, hex = "m1", action = "flag", payload = "m1:4".toByteArray()))
        assertEquals("\\Flagged", JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("add").getString(0))
        reply(fixture("send.json"))
        dispatcher.dispatch(Outbox(id = 2, hex = "m1", action = "send", payload = "just the text".toByteArray()))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("a@x.test", body.getJSONArray("to").getString(0))
        assertEquals("old", body.getString("subject"))
        assertEquals("just the text", body.getString("text"))
    }

    @Test
    fun permanentRejectionsAreDroppedAndTransientOnesRetried() = runBlocking {
        val dispatcher = HttpDispatcher(api)
        reply("{}", 422)
        try {
            dispatcher.dispatch(Outbox(id = 1, hex = "x", action = "send", payload = """{"to":["bad"],"subject":"","text":""}""".toByteArray()))
            fail("expected Rejected")
        } catch (e: Rejected) {
            assertEquals(422, e.code)
        }
        for (code in listOf(409, 429, 503)) {
            reply("{}", code)
            try {
                dispatcher.dispatch(Outbox(id = 1, hex = "x", action = "delete", payload = """{"hex":"x"}""".toByteArray()))
                fail("expected a retryable error for $code")
            } catch (e: HermesException.Network) {
                assertEquals(code, e.code)
            }
        }

        // The worker drops a rejected action and carries on with the next.
        val journal = Journal()
        val manager = Manager(journal)
        manager.enqueue("send", """{"to":["bad"],"subject":"","text":""}""".toByteArray(), "p1")
        manager.enqueue("delete", """{"hex":"m2"}""".toByteArray(), "m2")
        val rejected = mutableListOf<String>()
        val worker = Worker(manager, dispatcher, onRejected = { a, _ -> rejected.add(a.hex) })
        reply("{}", 422)
        reply("null")
        assertEquals(2, worker.drain())
        assertEquals(listOf("p1"), rejected)
        assertTrue(journal.outbox.isEmpty())
    }

    @Test
    fun otherActionsGoToTheFallback() = runBlocking {
        val seen = mutableListOf<String>()
        HttpDispatcher(api, fallback = Dispatcher { seen.add(it.action) })
            .dispatch(Outbox(action = "appointment_cancel", payload = "{}".toByteArray()))
        assertEquals(listOf("appointment_cancel"), seen)
        try {
            HttpDispatcher(api).dispatch(Outbox(action = "appointment_cancel"))
            fail("expected Rejected")
        } catch (_: Rejected) {
        }
    }
}
