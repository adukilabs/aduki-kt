package pro.aduki.sdk

import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.models.Draft
import pro.aduki.core.models.Upload
import pro.aduki.store.entities.Message
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The offline-first mail features (#24, #25, #26, #27, #28, #29, #31) against a fake mail host. */
class MailTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: Aduki

    /** `METHOD path` of each REST request. */
    private val rest = CopyOnWriteArrayList<String>()

    /** Each JMAP method call: name to arguments. */
    private val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()

    @Volatile private var boxes = listOf(box("b-inbox", "INBOX", "inbox"), box("b-sent", "Sent", "sent"), box("b-drafts", "Drafts", "drafts"))

    // What `Email/query` on a Message-ID header finds (a previous attempt).
    @Volatile private var previous: JSONObject? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val base = server.url("/").toString().trimEnd('/')
                if (path.startsWith("/jmap")) return jmap(request, path, base)
                rest += "${request.method} $path"
                return when {
                    path == "/v1/user" -> ok("""{"hex":"u1","tenant":"","email":"me@aduki.pro","owner":false}""")
                    request.method == "GET" && path.startsWith("/v1/user/mailbox?") ->
                        ok("""{"items":[${boxes.joinToString(",")}],"total":${boxes.size},"page":1,"pages":1}""")
                    request.method == "POST" && path == "/v1/user/mailbox" -> {
                        val name = JSONObject(request.body.readUtf8()).getString("name")
                        boxes = boxes + box("b-new", name, null)
                        ok(box("b-new", name, null))
                    }
                    path.startsWith("/v1/user/mail/changes?mailbox=b-inbox") -> ok(
                        """{"mailbox":"b-inbox","uidvalidity":7,"modseq":42,"reset":false,"more":false,
                           "created":[${row("m1", 1, "t1", "Lunch plans", "ada@example.com", 1000)},
                                      ${row("m2", 2, "t1", "Re: Lunch plans", "bob@example.com", 2000)},
                                      ${row("m3", 3, "t2", "Invoice 42", "billing@example.com", 3000)}],
                           "updated":[],"vanished":[]}"""
                    )
                    path.startsWith("/v1/user/mail/changes") -> ok("""{"uidvalidity":1,"modseq":1,"created":[],"updated":[],"vanished":[]}""")
                    path.startsWith("/v1/user/mail/search/") -> ok("""{"items":[${row("m9", 9, "t9", "Old invoice", "billing@example.com", 10, "b-archive")}],"total":1}""")
                    request.method == "PATCH" && path.endsWith("/mailbox") -> ok("""{"hex":"x","mailbox":"y","uid":5,"modseq":50}""")
                    request.method == "PATCH" -> MockResponse().setResponseCode(204)
                    request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path == "/v1/user/mail/send" -> ok("""{"hex":"srv-plain"}""")
                    path.startsWith("/v1/user/contacts") -> ok("""{"items":[],"total":0}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).store(dir.newFolder("db")).build()
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    private fun jmap(request: RecordedRequest, path: String, base: String): MockResponse = when {
        path == "/jmap/session" -> MockResponse().setBody(
            """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc"},"capabilities":{},
               "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
               "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
        )
        path == "/jmap/upload/acc" -> MockResponse().setBody("""{"accountId":"acc","blobId":"blob-up","type":"text/plain","size":3}""")
        path.startsWith("/jmap/download/acc/blob-a/") -> MockResponse().setBody("PDFDATA")
        path == "/jmap" -> {
            val body = JSONObject(request.body.readUtf8())
            val methods = body.getJSONArray("methodCalls")
            val replies = JSONArray()
            for (i in 0 until methods.length()) {
                val call = methods.getJSONArray(i)
                val name = call.getString(0)
                val args = call.getJSONObject(1)
                val id = call.getString(2)
                calls += name to args
                replies.put(JSONArray().put(name).put(answer(name, args)).put(id))
            }
            MockResponse().setBody(JSONObject().put("methodResponses", replies).put("sessionState", "s").toString())
        }
        else -> MockResponse().setResponseCode(404)
    }

    private fun answer(name: String, args: JSONObject): JSONObject = when (name) {
        "Email/get" -> {
            val ids = args.optJSONArray("ids")
            val wanted = if (ids != null && ids.length() > 0) ids.getString(0) else ""
            val list = when {
                args.has("#ids") -> previous?.let { JSONArray().put(it) } ?: JSONArray()
                wanted == "m1" && args.optJSONArray("properties").toString().contains("messageId") ->
                    JSONArray().put(JSONObject().put("id", "m1").put("messageId", JSONArray().put("orig@example.com")).put("references", JSONArray().put("root@example.com")))
                wanted == "m1" -> JSONArray().put(
                    JSONObject()
                        .put("id", "m1")
                        .put("bodyValues", JSONObject().put("0", JSONObject().put("value", "Noon?")).put("1", JSONObject().put("value", "<p>Noon?</p>")))
                        .put("textBody", JSONArray().put(JSONObject().put("partId", "0").put("type", "text/plain")))
                        .put("htmlBody", JSONArray().put(JSONObject().put("partId", "1").put("type", "text/html")))
                        .put("attachments", JSONArray().put(JSONObject().put("blobId", "blob-a").put("name", "menu.pdf").put("type", "application/pdf").put("size", 7).put("disposition", "attachment").put("cid", JSONObject.NULL)))
                )
                else -> JSONArray()
            }
            JSONObject().put("list", list).put("notFound", JSONArray())
        }
        "Email/query" -> JSONObject().put("ids", JSONArray())
        "Email/set" -> JSONObject()
            .put("created", if (args.has("create")) JSONObject().put("d", JSONObject().put("id", "srv-rich")) else JSONObject())
            .put("updated", JSONObject())
        "EmailSubmission/set" -> JSONObject().put("created", JSONObject().put("s", JSONObject().put("id", "sub1")))
        // Listed second but the default: a draft without `from` goes out as it.
        "Identity/get" -> JSONObject().put("list", JSONArray()
            .put(JSONObject().put("id", "id0").put("email", "team@example.com").put("name", "Team").put("isDefault", false))
            .put(JSONObject().put("id", "id1").put("email", "me@aduki.pro").put("name", "Me").put("isDefault", true)))
        "Mailbox/get" -> JSONObject().put("list", JSONArray()
            .put(JSONObject().put("id", "b-drafts").put("role", "drafts"))
            .put(JSONObject().put("id", "b-sent").put("role", "sent")))
        else -> JSONObject()
    }

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    private fun box(hex: String, name: String, role: String?) =
        """{"hex":"$hex","name":"$name","delimiter":"/","flags":[],"uidvalidity":7,"uidnext":1,"role":${role?.let { "\"$it\"" } ?: "null"},"messages":0,"unread":0}"""

    private fun row(hex: String, uid: Int, thread: String, subject: String, from: String, at: Long, mailbox: String = "b-inbox") =
        """{"hex":"$hex","uid":$uid,"thread":"$thread","subject":"$subject","sender":"$from","from":{"name":null,"email":"$from"},
            "to":[{"name":null,"email":"me@aduki.pro"}],"flags":[],"internaldate":"${Instant.ofEpochMilli(at)}","preview":"about $subject","mailbox":"$mailbox"}"""

    private suspend fun synced() {
        client.sync.all()
        withTimeout(5_000) { client.mail.observe("b-inbox")!!.first { it.size == 3 } }
    }

    private fun message(hex: String): Message? = client.mail.observe("b-inbox")!!.value.firstOrNull { it.hex == hex }

    @Test
    fun setFlagIsIdempotentAndBulkGoesInOneJmapCall() = runBlocking<Unit> {
        synced()

        client.mail.setFlag("m1", Message.SEEN, true)
        client.mail.setFlag("m1", Message.SEEN, true)
        assertEquals(1, client.sync.pending())

        client.mail.markAllRead("b-inbox")
        assertEquals(2, client.sync.pending())
        client.sync.flush()

        assertEquals(0, client.sync.pending())
        assertEquals(1, rest.count { it == "PATCH /v1/user/mail/m1/flags" })
        val bulk = calls.single { it.first == "Email/set" }.second.getJSONObject("update")
        assertEquals(setOf("m2", "m3"), bulk.keySet())
        assertEquals(true, bulk.getJSONObject("m2").get("keywords/\$seen"))
        assertEquals(0, client.mail.unread("b-inbox")!!.let { flow -> withTimeout(5_000) { flow.first { it == 0 } } })
    }

    @Test
    fun bulkMoveAndRemove() = runBlocking<Unit> {
        synced()
        client.mail.move(listOf("m1", "m2"), "b-sent")
        client.mail.remove(listOf("m3"))
        client.sync.flush()

        val move = calls.single { it.first == "Email/set" }.second.getJSONObject("update")
        assertEquals(JSONObject().put("b-sent", true).toString(), move.getJSONObject("m1").getJSONObject("mailboxIds").toString())
        assertTrue(rest.contains("DELETE /v1/user/mail/m3"))
        withTimeout(5_000) { client.mail.observe("b-inbox")!!.first { it.isEmpty() } }
    }

    @Test
    fun aMailboxCreatedOfflineAdoptsTheServerHexWithItsMessages() = runBlocking<Unit> {
        synced()
        server.dispatcher.let { real ->
            // Offline: the create and the move queue up.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
            val box = client.mail.createMailbox("Receipts")
            assertTrue(box.pending())
            client.mail.move("m3", box.hex)
            client.mail.renameMailbox(box.hex, "Receipts 2026")
            server.dispatcher = real
        }
        Thread.sleep(1_500)
        client.sync.flush()

        assertTrue(rest.contains("POST /v1/user/mailbox"))
        assertTrue(rest.contains("PATCH /v1/user/mail/m3/mailbox"))
        assertTrue(rest.contains("PATCH /v1/user/mailbox/b-new/name"))
        val listed = withTimeout(5_000) { client.mail.mailboxes()!!.first { list -> list.any { it.hex == "b-new" } } }
        assertFalse(listed.any { it.pending() })
        withTimeout(5_000) { client.mail.observe("b-new")!!.first { list -> list.any { it.hex == "m3" } } }
    }

    @Test
    fun searchThreadsAndConversations() = runBlocking<Unit> {
        synced()

        val found = withTimeout(5_000) { client.mail.search("lunch ada")!!.first { it.isNotEmpty() } }
        assertEquals(listOf("m1"), found.map { it.hex })
        val thread = withTimeout(5_000) { client.mail.thread("t1")!!.first { it.size == 2 } }
        assertEquals(listOf("m1", "m2"), thread.map { it.hex })
        val grouped = withTimeout(5_000) { client.mail.conversations("b-inbox")!!.first { it.size == 2 } }
        assertEquals(listOf("m3", "m2"), grouped.map { it.latest.hex })
        assertEquals(2, grouped[1].count)

        val remote = client.mail.find("invoice")
        assertEquals("m9", remote.single().hex)
    }

    @Test
    fun theBodyIsFetchedOnceThenReadOffline() = runBlocking<Unit> {
        synced()

        val body = client.mail.body("m1")
        assertEquals("Noon?", body.text)
        assertEquals("<p>Noon?</p>", body.html)
        assertEquals("menu.pdf", body.attachments.single().name)

        server.shutdown() // offline
        assertEquals(body, client.mail.body("m1"))
        assertEquals(1, calls.count { it.first == "Email/get" })
    }

    @Test
    fun anAttachmentDownloads() = runBlocking<Unit> {
        synced()
        val attachment = client.mail.attachments("m1").single()
        val file = client.mail.save(attachment, dir.newFile("menu.pdf"))
        assertEquals("PDFDATA", file.readText())
    }

    @Test
    fun aRichReplyGoesOverJmapWithThreadingAndBcc() = runBlocking<Unit> {
        synced()
        val draft = Draft(
            to = listOf("ada@example.com"),
            bcc = listOf("audit@example.com"),
            subject = "Re: Lunch plans",
            text = "Noon works",
            html = "<p>Noon works</p>",
            attachments = listOf(Upload("a.txt", "text/plain", bytes = "abc".toByteArray())),
            reply = "m1"
        )

        val sent = client.mail.send(draft)

        assertEquals(0, client.sync.pending())
        val create = calls.first { it.first == "Email/set" && it.second.has("create") }.second
            .getJSONObject("create").getJSONObject("d")
        assertEquals("audit@example.com", create.getJSONArray("bcc").getJSONObject(0).getString("email"))
        assertEquals("orig@example.com", create.getJSONArray("inReplyTo").getString(0))
        assertEquals(listOf("root@example.com", "orig@example.com"), create.getJSONArray("references").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("blob-up", create.getJSONArray("attachments").getJSONObject(0).getString("blobId"))
        assertEquals("text/html", create.getJSONArray("htmlBody").getJSONObject(0).getString("type"))
        assertTrue(create.getJSONArray("messageId").getString(0).endsWith("@aduki.pro"))
        val submit = calls.first { it.first == "EmailSubmission/set" }.second
        assertEquals("id1", submit.getJSONObject("create").getJSONObject("s").getString("identityId"))
        val answered = calls.first { it.first == "Email/set" && it.second.has("update") }.second
        assertEquals(true, answered.getJSONObject("update").getJSONObject("m1").get("keywords/\$answered"))
        assertTrue(sent.hex.startsWith("msg_"))
        assertFalse(rest.contains("POST /v1/user/mail/send"))
    }

    @Test
    fun aRetryAfterALostReplyDoesNotSendTwice() = runBlocking<Unit> {
        synced()
        previous = JSONObject().put("id", "srv-rich").put("keywords", JSONObject())

        client.mail.send(Draft(to = listOf("ada@example.com"), html = "<b>hi</b>"))

        assertEquals(0, client.sync.pending())
        assertNull(calls.firstOrNull { it.first == "EmailSubmission/set" })
    }

    @Test
    fun aPlainDraftStaysOnRest() = runBlocking<Unit> {
        synced()
        client.mail.send(Draft(to = listOf("ada@example.com"), cc = listOf("bob@example.com"), subject = "Hi", text = "plain"))
        assertTrue(rest.contains("POST /v1/user/mail/send"))
        assertNull(calls.firstOrNull { it.first == "EmailSubmission/set" })
    }

    @Test
    fun snoozeMovesAwayAndWakeBringsBackUnread() = runBlocking<Unit> {
        synced()
        client.mail.setFlag("m1", Message.SEEN, true)
        val until = Instant.now().plusSeconds(3600)

        client.mail.snooze("m1", until)
        client.sync.flush()

        val snoozed = withTimeout(5_000) { client.mail.mailboxes()!!.first { list -> list.any { it.name == "Snoozed" } } }
            .first { it.name == "Snoozed" }
        val moved = withTimeout(5_000) { client.mail.observe(snoozed.hex)!!.first { it.isNotEmpty() } }.single()
        assertEquals(until.epochSecond * 1000, moved.snoozed())
        assertTrue(rest.any { it == "PATCH /v1/user/mail/m1/flags" })

        assertEquals(0, client.mail.wake(Instant.now()))
        assertEquals(1, client.mail.wake(until.plusSeconds(1)))

        val back = withTimeout(5_000) { client.mail.observe("b-inbox")!!.first { list -> list.any { it.hex == "m1" } } }.first { it.hex == "m1" }
        assertFalse(back.seen())
        assertEquals(0L, back.snoozed())
        assertNotNull(message("m1"))
    }

    @Test
    fun findAndFetchUnsyncedMessageCanBeFlagged() = runBlocking<Unit> {
        synced()
        // m9 is in b-archive which was not synced locally.
        // 1. Without store=true, m9 is returned in memory but not stored.
        val unsaved = client.mail.find("invoice", store = false)
        assertEquals(1, unsaved.size)
        assertEquals("m9", unsaved[0].hex)
        try {
            client.mail.setFlag("m9", Message.FLAGGED, true)
            fail("expected IllegalArgumentException because m9 is not stored")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("m9"))
        }

        // 2. With store=true, m9 is loaded into the store and its mailbox created
        val saved = client.mail.find("invoice", store = true)
        assertEquals(1, saved.size)
        assertEquals("m9", saved[0].hex)

        // Setting a flag on m9 now succeeds
        client.mail.setFlag("m9", Message.FLAGGED, true)
        client.sync.flush()

        // 3. fetch(hex) reads back the stored message
        val fetched = client.mail.fetch("m9")
        assertEquals("m9", fetched.hex)
        assertEquals("b-archive", fetched.mailbox)
        assertTrue(fetched.flagged())
    }
}
