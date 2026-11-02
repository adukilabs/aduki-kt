package pro.aduki.sdk

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.AuthResult
import pro.aduki.core.models.Confidence
import pro.aduki.core.models.ReplySource
import pro.aduki.core.models.Transport
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** `Mail.conversation` against a fake mail host, with and without the server's reading. */
class ConversationTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val clients = ArrayList<Aduki>()

    /** Each JMAP method call: name to arguments. */
    private val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()

    /** The reading capability object in the session, or null for none. */
    @Volatile private var reading: String? = """{"properties":["aduki:text","aduki:html","aduki:reply","aduki:security"]}"""

    /** Refuse `aduki:reply` with a method error. */
    @Volatile private var refuse = false

    /** Answer the thread call with a page that is not JSON. */
    @Volatile private var garbage = false

    private val emails = mapOf(
        "m1" to email("m1", "root@example.com", null, "Lunch plans", "ada@example.com", "2026-11-04T09:00:00+03:00", "b-inbox", seen = true),
        "m2" to email("m2", "two@example.com", "root@example.com", "Re: Lunch plans", "bob@example.com", "2026-11-04T10:00:00Z", "b-inbox", seen = false),
        "m4" to email("m4", "three@example.com", "two@example.com", "Re: Lunch plans", "me@aduki.pro", "2026-11-04T11:00:00Z", "b-sent", seen = true)
    )

    private val texts = mapOf(
        "m1" to "Noon?",
        "m2" to "Works for me.\n\nOn Tue, 4 Nov 2026 at 09:00, Ada <ada@example.com> wrote:\n> Noon?",
        "m4" to "See you there.\n\n-- \nMe"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val base = server.url("/").toString().trimEnd('/')
                return when {
                    path == "/jmap/session" -> MockResponse().setBody(
                        """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc"},
                           "capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}${reading?.let { ",\"urn:aduki:params:jmap:reading\":$it" } ?: ""}},
                           "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
                           "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
                    )
                    path == "/jmap" -> jmap(request)
                    path == "/v1/user" -> ok("""{"hex":"u1","tenant":"","email":"me@aduki.pro","owner":false}""")
                    path.startsWith("/v1/user/mailbox?") -> ok(
                        """{"items":[${box("b-inbox", "INBOX", "inbox")},${box("b-sent", "Sent", "sent")}],"total":2,"page":1,"pages":1}"""
                    )
                    path.startsWith("/v1/user/mail/changes?mailbox=b-inbox") -> ok(
                        """{"mailbox":"b-inbox","uidvalidity":7,"modseq":42,"created":[${row("m1", 1, "Lunch plans", "ada@example.com", "root@example.com", 1000)},
                           ${row("m2", 2, "Re: Lunch plans", "bob@example.com", "two@example.com", 2000)}],"updated":[],"vanished":[]}"""
                    )
                    path.startsWith("/v1/user/mail/changes?mailbox=b-sent") -> ok(
                        """{"mailbox":"b-sent","uidvalidity":7,"modseq":42,"created":[${row("m4", 3, "Re: Lunch plans", "me@aduki.pro", "three@example.com", 3000, "b-sent")}],"updated":[],"vanished":[]}"""
                    )
                    path.startsWith("/v1/user/mail/changes") -> ok("""{"uidvalidity":1,"modseq":1,"created":[],"updated":[],"vanished":[]}""")
                    path.startsWith("/v1/user/contacts") -> ok("""{"items":[],"total":0}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.shutdown()
    }

    private fun client(store: File? = null): Aduki {
        val builder = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString())
        if (store != null) builder.store(store)
        return builder.build().also { clients += it }
    }

    private fun jmap(request: RecordedRequest): MockResponse {
        val body = JSONObject(request.body.readUtf8())
        val methods = body.getJSONArray("methodCalls")
        val replies = JSONArray()
        for (i in 0 until methods.length()) {
            val call = methods.getJSONArray(i)
            val name = call.getString(0)
            val args = call.getJSONObject(1)
            val id = call.getString(2)
            calls += name to args
            if (name == "Thread/get" && garbage) return MockResponse().setBody("<html>proxy error</html>")
            val properties = args.optJSONArray("properties")?.toString().orEmpty()
            if (name == "Email/get" && refuse && "aduki:reply" in properties) {
                replies.put(JSONArray().put("error").put(JSONObject().put("type", "invalidArguments")).put(id))
                continue
            }
            replies.put(JSONArray().put(name).put(answer(name, args)).put(id))
        }
        return MockResponse().setBody(JSONObject().put("methodResponses", replies).put("sessionState", "s").toString())
    }

    private fun answer(name: String, args: JSONObject): JSONObject = when (name) {
        "Thread/get" -> {
            val id = args.getJSONArray("ids").getString(0)
            if (id == "t1") JSONObject().put("list", JSONArray().put(JSONObject().put("id", "t1").put("emailIds", JSONArray(listOf("m1", "m2", "m4")))))
            else JSONObject().put("list", JSONArray()).put("notFound", JSONArray().put(id))
        }
        "Mailbox/get" -> JSONObject().put(
            "list",
            JSONArray().put(JSONObject().put("id", "b-inbox").put("role", "inbox")).put(JSONObject().put("id", "b-sent").put("role", "sent"))
        )
        "Email/get" -> {
            val properties = args.optJSONArray("properties")?.let { p -> (0 until p.length()).map { p.getString(it) } }.orEmpty()
            val ids = if (args.has("#ids")) listOf("m1", "m2", "m4") else args.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
            val list = JSONArray()
            ids.forEach { hex ->
                val out = JSONObject().put("id", hex)
                when {
                    "messageId" in properties -> JSONObject(emails.getValue(hex).toString()).let { list.put(it) }
                    "aduki:reply" in properties -> {
                        out.put("aduki:reply", replyOf(hex) ?: JSONObject.NULL)
                        if ("aduki:security" in properties) out.put("aduki:security", if (hex == "m2") security() else JSONObject.NULL)
                        list.put(out)
                    }
                    args.has("aduki:bodyMode") -> list.put(
                        out.put("aduki:text", texts.getValue(hex)).put("aduki:html", JSONObject.NULL)
                            .put("aduki:hasText", true).put("aduki:hasHtml", false).put("attachments", JSONArray())
                    )
                    else -> list.put(
                        out.put("bodyValues", JSONObject().put("0", JSONObject().put("value", texts.getValue(hex))))
                            .put("textBody", JSONArray().put(JSONObject().put("partId", "0").put("type", "text/plain")))
                            .put("htmlBody", JSONArray()).put("attachments", JSONArray())
                    )
                }
            }
            JSONObject().put("list", list)
        }
        else -> JSONObject()
    }

    private fun replyOf(hex: String): JSONObject? = when (hex) {
        "m1" -> JSONObject().put("text", "Noon?").put("html", JSONObject.NULL).put("quote", JSONObject.NULL)
            .put("signature", JSONObject.NULL).put("forwarded", JSONObject.NULL).put("confidence", "low")
        "m2" -> JSONObject().put("text", "Works for me.").put("html", "<p>Works for me.</p>")
            .put("quote", JSONObject().put("start", 15).put("from", "root@example.com").put("author", "Ada"))
            .put("signature", JSONObject.NULL).put("forwarded", JSONObject.NULL).put("confidence", "high")
        else -> null
    }

    private fun security() = JSONObject(
        """{"transport":{"tls":true,"version":"TLSv1.3","cipher":"TLS_AES_128_GCM_SHA256","from":"mx.example.com"},
           "spf":{"result":"pass","domain":"example.com"},"dkim":[{"result":"pass","domain":"example.com","selector":"s1"}],
           "dmarc":{"result":"pass","policy":"reject","domain":"example.com"},"signedBy":"example.com","mailedBy":"mail.example.com","internal":false}"""
    )

    private fun email(hex: String, id: String, parent: String?, subject: String, from: String, sent: String, box: String, seen: Boolean): JSONObject =
        JSONObject()
            .put("id", hex).put("threadId", "t1")
            .put("messageId", JSONArray().put(id))
            .put("inReplyTo", parent?.let { JSONArray().put(it) } ?: JSONObject.NULL)
            .put("references", parent?.let { JSONArray().put(it) } ?: JSONObject.NULL)
            .put("subject", subject)
            .put("sentAt", sent).put("receivedAt", Instant.parse("2026-11-04T12:00:00Z").toString())
            .put("from", JSONArray().put(JSONObject().put("name", JSONObject.NULL).put("email", from)))
            .put("to", JSONArray().put(JSONObject().put("name", "Me").put("email", "me@aduki.pro")))
            .put("cc", JSONObject.NULL)
            .put("keywords", if (seen) JSONObject().put("\$seen", true) else JSONObject())
            .put("mailboxIds", JSONObject().put(box, true))
            .put("attachments", JSONArray())

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    private fun box(hex: String, name: String, role: String?) =
        """{"hex":"$hex","name":"$name","delimiter":"/","flags":[],"uidvalidity":7,"uidnext":1,"role":${role?.let { "\"$it\"" } ?: "null"},"messages":0,"unread":0}"""

    private fun row(hex: String, uid: Int, subject: String, from: String, id: String, at: Long, mailbox: String = "b-inbox") =
        """{"hex":"$hex","uid":$uid,"thread":"t1","subject":"$subject","sender":"$from","from":{"name":null,"email":"$from"},
            "to":[{"name":null,"email":"me@aduki.pro"}],"flags":["\\Seen"],"internaldate":"${Instant.ofEpochMilli(at)}","preview":"about $subject",
            "message_id":"$id","mailbox":"$mailbox"}"""

    private fun props(call: Pair<String, JSONObject>) = call.second.optJSONArray("properties")?.toString().orEmpty()

    @Test
    fun theServersReplyAndSecurityAreUsedAndOnlyMissingBodiesAreRead() = runBlocking<Unit> {
        val c = client().mail.conversation("t1", refresh = false)
        assertEquals(listOf("m1", "m2", "m4"), c.messages.map { it.hex })
        assertEquals(listOf(null, "m1", "m2"), c.messages.map { it.parent })
        assertEquals("Lunch plans", c.subject)
        assertFalse(c.offline)

        val m2 = c.messages[1]
        assertEquals("Works for me.", m2.reply.text)
        assertEquals("<p>Works for me.</p>", m2.reply.html)
        assertEquals(ReplySource.Server, m2.reply.source)
        assertEquals(Confidence.High, m2.reply.confidence)
        assertEquals("m1", m2.reply.quotedFrom)
        assertEquals("Ada", m2.reply.author)
        assertEquals(15, m2.reply.quoteStart)
        assertTrue(m2.unread)
        val security = m2.security!!
        assertEquals(Transport(true, "TLSv1.3", "TLS_AES_128_GCM_SHA256", "mx.example.com"), security.transport)
        assertEquals(AuthResult("pass", "example.com"), security.spf)
        assertEquals(listOf(AuthResult("pass", "example.com", selector = "s1")), security.dkim)
        assertEquals(AuthResult("pass", "example.com", policy = "reject"), security.dmarc)
        assertEquals("example.com", security.signedBy)
        assertEquals("mail.example.com", security.mailedBy)
        assertTrue(c.security.available && c.security.encrypted)

        // A low-confidence reply carries the whole text: no body fetch for m1.
        assertEquals("Noon?", c.messages[0].reply.text)
        assertEquals(Confidence.Low, c.messages[0].reply.confidence)
        // m4 has no reply: its body alone is read, and cut on the device.
        assertEquals("See you there.", c.messages[2].reply.text)
        assertEquals("Me", c.messages[2].reply.signature)
        val bodies = calls.filter { it.first == "Email/get" && it.second.has("aduki:bodyMode") }
        assertEquals(1, bodies.size)
        assertEquals(listOf("m4"), bodies.single().second.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test
    fun withoutTheCapabilityTheBodiesAreCutOnTheDevice() = runBlocking<Unit> {
        reading = null
        val c = client().mail.conversation("t1", refresh = false)
        assertEquals(3, c.messages.size)
        assertTrue(calls.none { "aduki:reply" in props(it) })
        val m2 = c.messages.first { it.hex == "m2" }
        assertEquals("Works for me.", m2.reply.text)
        assertEquals(ReplySource.Heuristic, m2.reply.source)
        assertEquals(Confidence.High, m2.reply.confidence)
        assertEquals("Ada", m2.reply.author)
        assertNull(m2.security)
        assertFalse(c.security.available)
        // One batch for every body.
        assertEquals(1, calls.count { it.first == "Email/get" && "bodyValues" in props(it) })
    }

    @Test
    fun aReadingCapabilityWithoutThePropertiesIsNotAskedForThem() = runBlocking<Unit> {
        reading = "{}"
        val c = client().mail.conversation("t1", refresh = false)
        assertTrue(calls.none { "aduki:reply" in props(it) })
        assertEquals("Works for me.", c.messages.first { it.hex == "m2" }.reply.text)
    }

    @Test
    fun aRefusedReadingFallsBackToTheBodies() = runBlocking<Unit> {
        refuse = true
        val c = client().mail.conversation("t1", refresh = false)
        assertEquals(3, c.messages.size)
        val m2 = c.messages.first { it.hex == "m2" }
        assertEquals("Works for me.", m2.reply.text)
        assertEquals(ReplySource.Heuristic, m2.reply.source)
    }

    @Test
    fun theReadingIsKeptInMemoryAndRefreshAsksAgain() = runBlocking<Unit> {
        val mail = client().mail
        mail.conversation("t1", refresh = false)
        mail.conversation("t1", refresh = false)
        assertEquals(1, calls.count { "aduki:reply" in props(it) })
        assertEquals(2, calls.count { it.first == "Thread/get" })
        mail.conversation("t1", refresh = true)
        assertEquals(2, calls.count { "aduki:reply" in props(it) })
    }

    @Test
    fun failuresStayWithinTheContract() = runBlocking<Unit> {
        val mail = client().mail
        try {
            mail.conversation("nope", refresh = false)
            fail("unknown thread")
        } catch (e: AdukiException.Api) {
            assertEquals(404, e.status)
        }
        try {
            mail.conversation(" ", refresh = false)
            fail("blank id")
        } catch (_: IllegalArgumentException) {
        }
        garbage = true
        try {
            mail.conversation("t1", refresh = false)
            fail("not JSON")
        } catch (_: AdukiException.Protocol) {
        }
    }

    @Test
    fun theFlowServesTheStoreFirstThenTheServerAndTheStoreOffline() = runBlocking<Unit> {
        val folder = dir.newFolder("db")
        val client = client(folder)
        client.sync.all()
        val both = withTimeout(10_000) { client.mail.conversation("t1").take(2).toList() }
        assertTrue(both[0].offline)
        assertEquals(setOf("m1", "m2", "m4"), both[0].messages.map { it.hex }.toSet())
        assertFalse(both[1].offline)
        assertEquals(listOf(null, "m1", "m2"), both[1].messages.map { it.parent })

        // Offline: the stored thread, with the headers the server gave earlier, and the bodies cached.
        server.shutdown()
        val offline = client.mail.conversation("t1", refresh = false)
        assertTrue(offline.offline)
        assertEquals(listOf(null, "m1", "m2"), offline.messages.map { it.parent })
        assertEquals("Works for me.", offline.messages.first { it.hex == "m2" }.reply.text)
        assertEquals("See you there.", offline.messages.first { it.hex == "m4" }.reply.text)

        // A thread the store does not have fails as the network does.
        try {
            client.mail.conversation("t9", refresh = false)
            fail("offline without a stored thread")
        } catch (e: Exception) {
            assertTrue(e.javaClass.name, e is AdukiException || e is java.io.IOException)
        }
    }

    @Test
    fun aNewClientOfflineStillShowsEveryStoredMessage() = runBlocking<Unit> {
        val folder = dir.newFolder("db")
        val first = client(folder)
        first.sync.all()
        first.mail.body("m2")
        first.close()
        clients.remove(first)
        server.shutdown()
        val c = client(folder).mail.conversation("t1", refresh = false)
        assertTrue(c.offline)
        assertEquals(setOf("m1", "m2", "m4"), c.messages.map { it.hex }.toSet())
        assertEquals("Works for me.", c.messages.first { it.hex == "m2" }.reply.text)
    }
}
