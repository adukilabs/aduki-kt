package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.CallEntry
import pro.aduki.core.models.TokenStore
import pro.aduki.core.models.Tokens
import pro.aduki.crypto.dpop.Software
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** `Aduki.calls`: meetings and calls with an `audio` token from the client's own Aduki ID session. */
class CallsFacadeTest {

    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val rotations = AtomicInteger()
    private val routes = HashMap<String, MockResponse>()

    private class Memory(var tokens: Tokens?) : TokenStore {
        override fun load() = tokens
        override fun save(tokens: Tokens) { this.tokens = tokens }
        override fun clear() { tokens = null }
    }

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                if (request.path == "/id/tokens") {
                    val audience = JSONObject(request.body.readUtf8()).optString("audience")
                    val n = rotations.incrementAndGet()
                    return MockResponse().setBody("""{"success":true,"data":{"access":"eyJ.$audience.$n","refresh":"rt_${n + 1}","expires":600}}""")
                }
                synchronized(routes) { routes["${request.method} ${request.path}"] }?.let { return it }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun teardown() = server.shutdown()

    private fun route(method: String, path: String, body: String, code: Int = 200) = synchronized(routes) {
        routes["$method $path"] = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$body}""")
    }

    private fun client(store: Memory, dpop: Boolean = true): Aduki = Aduki.builder()
        .endpoint(server.url("/v1").toString())
        .identity(server.url("/id").toString())
        .calls(server.url("/calls/v1").toString())
        .tokens(store)
        .apply { if (dpop) dpop(Software.p256()) }
        .build()

    @Test
    fun aMeetingIsMadeWithAnAudioTokenAndTheRotatedRefreshTokenIsSaved() = runBlocking {
        val store = Memory(Tokens("eyJ.mail", "rt_1", "600", "00000000000000ab"))
        route(
            "POST", "/calls/v1/calls",
            """{"call":"04e61991c100cebb","kind":"meeting","state":"open","code":"abc-defg-hij","join":{"signal":"wss://x","room":"04e61991c100cebb"}}""",
            201
        )
        val client = client(store)
        val meeting = client.calls.meeting(title = "Weekly")

        assertEquals("abc-defg-hij", meeting.code)
        assertEquals(server.url("/calls").toString() + "/call/abc-defg-hij", meeting.link)
        val sent = requests.single { it.path == "/calls/v1/calls" }
        assertEquals("Bearer eyJ.audio.1", sent.getHeader("Authorization"))
        assertEquals("rt_2", store.tokens!!.refresh)
        assertEquals("rt_2", client.session.tokens.value!!.refresh)

        // A second call reuses the cached audio token: no new rotation.
        client.calls.meeting()
        assertEquals(1, rotations.get())
    }

    @Test
    fun joinAndRestartTakeACodeOrALink() = runBlocking {
        val store = Memory(Tokens("eyJ.mail", "rt_1", "600", "00000000000000ab"))
        val call = "04e61991c100cebb"
        route("POST", "/calls/v1/calls/abc-defg-hij/tokens", """{"ticket":"t1","expires":60,"call":"$call"}""", 201)
        route("GET", "/calls/v1/calls/abc-defg-hij", """{"call":"$call","kind":"meeting","state":"expired","code":"abc-defg-hij"}""")
        route("POST", "/calls/v1/calls/$call/reopen", """{"call":"$call","kind":"meeting","state":"open","restarted":true,"code":"abc-defg-hij"}""")
        val client = client(store)

        val entry = client.calls.join("https://calls.aduki.pro/call/abc-defg-hij")
        assertEquals("t1", (entry as CallEntry.Ticket).ticket.ticket)
        assertTrue(client.calls.restart("ABCDEFGHIJ").restarted)
        assertEquals(listOf("/calls/v1/calls/abc-defg-hij/tokens", "/calls/v1/calls/abc-defg-hij", "/calls/v1/calls/$call/reopen"),
            requests.map { it.path }.filter { it!!.startsWith("/calls") })
    }

    @Test
    fun withoutADpopKeyOrASessionCallsFailBeforeAnyRequest() = runBlocking {
        val noKey = client(Memory(Tokens("eyJ.mail", "rt_1", "600", "s")), dpop = false)
        assertThrows(IllegalStateException::class.java) { runBlocking { noKey.calls.meeting() } }
        val signedOut = Aduki.builder().token("eyJ.mail").dpop(Software.p256())
            .identity(server.url("/id").toString()).calls(server.url("/calls/v1").toString()).build()
        assertThrows(AdukiException.Unauthorized::class.java) { runBlocking { signedOut.calls.meeting() } }
        assertEquals(0, requests.size)
    }
}
