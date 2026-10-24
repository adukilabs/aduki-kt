package pro.aduki.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.store.entities.Message
import java.util.concurrent.CopyOnWriteArrayList

/** `Builder.store` wires the outbox, repositories and sync engines (#43, #44, #30). */
class StoreTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()

    // What the fake server holds; tests change it between syncs.
    @Volatile private var boxes = listOf(INBOX, SENT)
    @Volatile private var failing = false
    private var client: Aduki? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += "${request.method} $path"
                if (failing) return MockResponse().setResponseCode(500)
                return when {
                    path == "/v1/user" -> ok("""{"hex":"u1","tenant":"t1","email":"a@aduki.pro","owner":false}""")
                    path.startsWith("/v1/user/mailbox?") -> ok("""{"items":[${boxes.joinToString(",")}],"total":${boxes.size},"page":1,"pages":1}""")
                    path.startsWith("/v1/user/mail/changes?mailbox=b-inbox") -> ok(
                        """{"mailbox":"b-inbox","uidvalidity":7,"modseq":42,"reset":false,"more":false,
                           "created":[{"hex":"m1","uid":1,"subject":"Hi","sender":"x@example.com","flags":[],"internaldate":"2026-10-24T10:00:00","preview":"hello"}],
                           "updated":[],"vanished":[]}"""
                    )
                    path.startsWith("/v1/user/mail/changes?mailbox=b-sent") -> ok(
                        """{"mailbox":"b-sent","uidvalidity":9,"modseq":3,"reset":false,"more":false,"created":[],"updated":[],"vanished":[]}"""
                    )
                    path.startsWith("/v1/user/contacts") -> ok("""{"items":[],"total":0}""")
                    path == "/v1/user/mail/send" -> ok("""{"hex":"srv1"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        client?.close()
        server.shutdown()
    }

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    private fun build(): Aduki = Aduki.builder()
        .key("opaque")
        .endpoint(server.url("/v1").toString())
        .store(dir.newFolder("db"))
        .build()
        .also { client = it }

    @Test
    fun writesWithoutAStoreThrow() = runBlocking<Unit> {
        val bare = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).build()
        for (write in listOf<suspend () -> Unit>(
            { bare.mail.send(listOf("a@example.com"), "s", "b") },
            { bare.mail.flag("m1", Message.SEEN) },
            { bare.mail.move("m1", "b-sent") },
            { bare.mail.remove("m1") }
        )) {
            val e = runCatching { write() }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
            assertEquals("no local store", e?.message)
        }
        assertNull(bare.mail.mailboxes())
    }

    @Test
    fun allLoadsTheMailboxListThenSyncsEveryMailbox() = runBlocking<Unit> {
        val aduki = build()

        assertTrue(aduki.sync.all())

        val listed = withTimeout(5_000) { aduki.mail.mailboxes()!!.first { it.size == 2 } }
        assertEquals(listOf("INBOX", "Sent"), listed.map { it.name })
        assertEquals("inbox", listed.first().role)
        val inbox = withTimeout(5_000) { aduki.mail.observe("b-inbox")!!.first { it.isNotEmpty() } }
        assertEquals("m1", inbox.single().hex)
        assertTrue(paths.any { it.startsWith("GET /v1/user/mail/changes?mailbox=b-sent") })
    }

    @Test
    fun aSecondSyncKeepsTheCursorOfKnownMailboxes() = runBlocking<Unit> {
        val aduki = build()
        aduki.sync.all()
        paths.clear()

        aduki.sync.all()

        assertTrue(paths.any { it.startsWith("GET /v1/user/mail/changes?mailbox=b-inbox&since=42&") && "uidvalidity=7" in it })
    }

    @Test
    fun aMailboxDeletedOnTheServerIsDroppedWithItsMessages() = runBlocking<Unit> {
        val aduki = build()
        aduki.sync.all()
        boxes = listOf(SENT)

        assertEquals(listOf("b-sent"), aduki.sync.mailboxes())

        withTimeout(5_000) { aduki.mail.mailboxes()!!.first { it.size == 1 } }
        withTimeout(5_000) { aduki.mail.observe("b-inbox")!!.first { it.isEmpty() } }
    }

    @Test
    fun aRoleNameResolvesToItsMailbox() = runBlocking<Unit> {
        val aduki = build()

        assertTrue(aduki.sync.all(listOf("inbox")))
        assertFalse(aduki.sync.all(listOf("nope")))

        assertTrue(paths.any { it.startsWith("GET /v1/user/mail/changes?mailbox=b-inbox") })
        assertFalse(paths.any { "mailbox=inbox" in it || "mailbox=nope" in it })
    }

    @Test
    fun stateRecordsSuccessAndFailure() = runBlocking<Unit> {
        val aduki = build()
        assertEquals(SyncState(), aduki.sync.state.value)

        aduki.sync.all()
        val done = aduki.sync.state.value
        assertFalse(done.running)
        assertTrue(done.synced > 0)
        assertNull(done.error)

        failing = true
        assertThrows(Exception::class.java) { runBlocking { aduki.sync.all() } }
        val failed = aduki.sync.state.value
        assertFalse(failed.running)
        assertNotNull(failed.error)
        assertEquals(done.synced, failed.synced)
    }

    @Test
    fun sendGoesThroughTheOutboxAndTheCountFollows() = runBlocking<Unit> {
        val aduki = build()
        assertEquals(0, aduki.sync.outbox.value)

        failing = true
        aduki.mail.send(listOf("a@example.com"), "s", "body")
        assertEquals(1, aduki.sync.outbox.value)
        assertEquals(1, aduki.sync.pending())

        failing = false
        Thread.sleep(1_500) // past the first retry delay
        aduki.sync.flush()
        assertEquals(0, aduki.sync.outbox.value)
        assertTrue(paths.contains("POST /v1/user/mail/send"))
    }

    private companion object {
        const val INBOX = """{"hex":"b-inbox","name":"INBOX","delimiter":"/","flags":[],"uidvalidity":7,"uidnext":2,"role":"inbox","messages":1,"unread":1}"""
        const val SENT = """{"hex":"b-sent","name":"Sent","delimiter":"/","flags":[],"uidvalidity":9,"uidnext":1,"role":"sent","messages":0,"unread":0}"""
    }
}
