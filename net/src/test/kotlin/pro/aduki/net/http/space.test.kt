package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import pro.aduki.crypto.dpop.Software
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** The Space client against a fake Space and a fake Aduki ID, with DPoP-bound tokens as Space requires. */
class SpaceTest {
    private lateinit var server: MockWebServer
    private val key = Software.p256()
    private val dpop = Dpop(key)
    private val http = OkHttpClient.Builder().addInterceptor(dpop).build()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var issued = 0
    @Volatile private var spaceAnswers = ArrayDeque<MockResponse>()

    private fun jwt(n: Int): String {
        val claims = """{"sub":"a1","aud":"space","jti":"$n","cnf":{"jkt":"${key.thumbprint()}"}}"""
        return "${b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray())}.${b64.encodeToString(claims.toByteArray())}.sig"
    }

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/id/v1/sessions")) {
                    issued++
                    return MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"session":"s1","access":"${jwt(issued)}","refresh":"adkr_$issued","expires":600}}""")
                }
                if (path.startsWith("/id/v1/tokens")) {
                    issued++
                    return MockResponse().setBody("""{"success":true,"data":{"access":"${jwt(issued)}","refresh":"adkr_$issued","expires":600}}""")
                }
                seen += request
                synchronized(this@SpaceTest) { return spaceAnswers.removeFirst() }
            }
        }
        server.start()
    }
    @After fun teardown() { server.shutdown() }

    private fun space(): Space {
        val id = Id(http, server.url("/id/v1").toString())
        id.signIn("ada@acme.example", "pw", code = "123456", audience = "space")
        return Space(http, id, server.url("/space/v1").toString())
    }

    private fun answer(vararg responses: MockResponse) = synchronized(this) { spaceAnswers = ArrayDeque(responses.toList()) }
    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")
    private fun failure(code: Int, kind: String, details: String? = null) = MockResponse().setResponseCode(code)
        .setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m"${details?.let { ""","details":$it""" } ?: ""}}}""")

    private val hexA = "9f3a0c71e2b45d08"

    @Test
    fun everyCallCarriesTheDpopSchemeAndAProofBoundToTheTokenAndUrl() {
        val client = space()
        answer(ok("""{"items":[],"next":null}"""))
        client.tasks(status = "open", q = "ssl", limit = 20)
        val request = seen.single()
        val token = request.getHeader("Authorization")!!
        assertTrue(token.startsWith("DPoP "))
        val proof = request.getHeader("DPoP")!!.split('.')
        val claims = JSONObject(String(Base64.getUrlDecoder().decode(proof[1])))
        assertEquals("GET", claims.getString("htm"))
        assertEquals(server.url("/space/v1/tasks").toString(), claims.getString("htu"))
        assertEquals(b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.removePrefix("DPoP ").toByteArray())), claims.getString("ath"))
        val query = request.requestUrl!!
        assertEquals("open", query.queryParameter("status"))
        assertEquals("ssl", query.queryParameter("q"))
        assertEquals("20", query.queryParameter("limit"))
        assertNull(query.queryParameter("mine")) // false filters are not sent: the server refuses unknown parameters only, but stays minimal
    }

    @Test
    fun aListIsAPageAndAllFollowsTheCursor() {
        val client = space()
        answer(
            ok("""{"items":[{"id":"a"},{"id":"b"}],"next":"CUR1"}"""),
            ok("""{"items":[{"id":"c"}],"next":null}""")
        )
        val ids = client.all("tasks", size = 2).map { it.getString("id") }.toList()
        assertEquals(listOf("a", "b", "c"), ids)
        assertNull(seen[0].requestUrl!!.queryParameter("after"))
        assertEquals("CUR1", seen[1].requestUrl!!.queryParameter("after"))
        assertEquals("2", seen[1].requestUrl!!.queryParameter("limit"))

        answer(ok("""{"items":[{"id":"z"}],"next":"N"}"""))
        val page = client.list("tickets", limit = 1)
        assertEquals("N", page.next)
        try { client.list("tasks", limit = 101); fail() } catch (_: IllegalArgumentException) {}
        try { client.list("tasks", limit = 0); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun writesSendAnIdempotencyKeyAndEditsSendIfMatchFromTheEtag() {
        val client = space()
        answer(
            ok("""{"id":"$hexA","title":"T"}""", 201).setHeader("ETag", "\"1a2b\""),
            ok("""{"id":"$hexA","title":"T"}""").setHeader("ETag", "\"1a2b\""),
            ok("""{"id":"$hexA","title":"U"}""").setHeader("ETag", "\"1a2c\"")
        )
        val created = client.addTask(JSONObject().put("title", "T"))
        val post = seen[0]
        assertEquals("POST", post.method)
        UUIDCheck(post.getHeader("Idempotency-Key")!!)
        assertEquals("T", JSONObject(post.body.readUtf8()).getString("title"))
        assertEquals("1a2b", created.etag)
        assertEquals(201, created.status)

        val read = client.task(hexA)
        val edited = client.editTask(hexA, JSONObject().put("title", "U"), version = read.etag)
        val patch = seen[2]
        assertEquals("PATCH", patch.method)
        assertEquals("\"1a2b\"", patch.getHeader("If-Match"))
        assertEquals("1a2c", edited.etag)
    }

    private fun UUIDCheck(text: String) { java.util.UUID.fromString(text) }

    @Test
    fun checkinAndTicketBodiesUseTheDocumentedFields() {
        val client = space()
        answer(ok("""{"id":"c1"}""", 201), ok("""{"message":{"id":"1042"},"ticket":{}}""", 201), ok("""{"id":"t1"}""", 201))
        client.addCheckin(hexA, "Added index benchmarks", minutes = 150, proof = "https://git.example/pr/42")
        val checkin = JSONObject(seen[0].body.readUtf8())
        assertEquals(setOf("body", "minutes", "proof"), checkin.keySet())
        assertEquals(150, checkin.getInt("minutes"))
        assertEquals("/space/v1/tasks/$hexA/checkins", seen[0].path)

        client.reply(hexA, "Resizing now", internal = true)
        val reply = JSONObject(seen[1].body.readUtf8())
        assertEquals(setOf("body", "internal"), reply.keySet())
        assertTrue(reply.getBoolean("internal"))
        assertEquals("/space/v1/tickets/$hexA/messages", seen[1].path)

        client.addTicket(JSONObject().put("client", hexA).put("title", "Down"))
        assertEquals("/space/v1/tickets", seen[2].path)
        try { client.addCheckin(hexA, "x", minutes = -1); fail() } catch (_: IllegalArgumentException) {}
        try { client.task("TSK-0042"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun searchValidatesAndReturnsCollectionIdPairs() {
        val client = space()
        answer(ok("""{"items":[{"collection":"tasks","id":"$hexA"}],"source":"typesense","stale":false}"""))
        val found = client.search(" ssl ", entity = "tasks", limit = 5)
        assertEquals(hexA, found.single().getString("id"))
        assertEquals("ssl", seen[0].requestUrl!!.queryParameter("q"))
        try { client.search("", null, null); fail() } catch (_: IllegalArgumentException) {}
        try { client.search("x".repeat(201)); fail() } catch (_: IllegalArgumentException) {}
        try { client.search("x", limit = 51); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun errorEnvelopesBecomeTypedExceptionsWithKindDetailsAndRetryAfter() {
        val client = space()
        answer(
            failure(409, "entity.conflict", """{"kind":"invoice.nodraft"}"""),
            failure(403, "sod.refused", """{"rule":"sod_approve"}"""),
            failure(503, "tenant.syncing").setHeader("Retry-After", "2"),
            failure(422, "input.invalid"),
            MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>")
        )
        val conflict = fails { client.call("POST", "tasks/$hexA/invoices", body = JSONObject().put("mode", "attach")) }
        assertEquals(409, conflict.status); assertEquals("entity.conflict", conflict.kind)
        assertEquals("invoice.nodraft", JSONObject(conflict.details!!).getString("kind"))
        assertEquals("sod_approve", JSONObject(fails { client.call("POST", "payroll/batches/$hexA/decisions") }.details!!).getString("rule"))
        val syncing = fails { client.tasks() }
        assertEquals("tenant.syncing", syncing.kind); assertEquals(2L, syncing.retryAfter)
        assertEquals("input.invalid", fails { client.tasks() }.kind)
        val html = fails { client.tasks() }
        assertEquals(502, html.status); assertEquals("http.502", html.kind); assertNull(html.retryAfter)
    }

    private fun fails(block: () -> Unit): AdukiException.Api =
        try { block(); throw AssertionError("expected an Api failure") } catch (e: AdukiException.Api) { e }

    @Test
    fun a401RenewsTheTokenOnceThenFailsAsUnauthorized() {
        val client = space()
        answer(failure(401, "auth.stale"), ok("""{"items":[],"next":null}"""))
        client.tasks()
        assertEquals(2, seen.size)
        assertFalse(seen[0].getHeader("Authorization") == seen[1].getHeader("Authorization"))
        assertEquals(2, issued) // the sign-in and one refresh

        seen.clear()
        answer(failure(401, "auth.invalid"), failure(401, "auth.invalid"))
        try { client.tasks(); fail() } catch (_: AdukiException.Unauthorized) {}
        assertEquals(2, seen.size) // never loops
    }

    @Test
    fun aSuccessThatIsNotAnEnvelopeIsAProtocolError() {
        val client = space()
        answer(MockResponse().setBody("""{"items":[]}"""))
        try { client.tasks(); fail() } catch (_: AdukiException.Protocol) {}
    }

    @Test
    fun aBadIdempotencyKeyIsRefusedBeforeAnyRequest() {
        val client = space()
        try { client.addTask(JSONObject().put("title", "T"), idempotency = "not-a-uuid"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(seen.isEmpty())
    }
}
