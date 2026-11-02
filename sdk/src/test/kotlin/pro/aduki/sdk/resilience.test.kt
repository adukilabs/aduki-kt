package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Tokens
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Storage
import pro.aduki.sync.outbox.Worker
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Network and server failures reach the app as AdukiException (or
 * IOException), and background work never throws to an uncaught handler.
 */
class ResilienceTest {

    private lateinit var server: MockWebServer
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    // What the server answers, by path prefix; anything else is a 404.
    private val routes = linkedMapOf<String, (RecordedRequest) -> MockResponse>()

    @Before
    fun setup() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return routes.entries.firstOrNull { path.startsWith(it.key) }?.value?.invoke(request)
                    ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    // Coroutines' stack trace recovery (tests run with -ea) may copy an
    // exception and chain the original as its cause.
    private fun causes(e: Throwable): List<Throwable> = generateSequence(e) { it.cause }.take(10).toList()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private fun client(): Aduki {
        val client = Aduki.builder()
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .token("eyJ.old")
            .build()
        client.session.update(Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "ab"))
        return client
    }

    private fun session() {
        val base = server.url("").toString().trimEnd('/')
        routes["/jmap/session"] = {
            json(200, """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc"},"capabilities":{},
                "apiUrl":"$base/jmap","uploadUrl":"$base/u","downloadUrl":"$base/d"}""")
        }
    }

    @Test
    fun aMalformedServerListIsAnAdukiExceptionNotAJsonException() = runBlocking {
        session()
        routes["/jmap"] = { json(200, """{"methodResponses":[["Email/get",{"list":["not an object"]},"0"]]}""") }
        val e = assertThrows(AdukiException.Protocol::class.java) { runBlocking { client().mail.headers("m1") } }
        assertTrue("cause ${e.cause}", causes(e).any { it is org.json.JSONException })
    }

    @Test
    fun anHtmlErrorPageIsANetworkFailureWithItsStatus() = runBlocking {
        routes["/v1/user/pages"] = {
            MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>Bad Gateway</html>")
        }
        val e = assertThrows(AdukiException.Network::class.java) { runBlocking { client().scheduling.services() } }
        assertEquals(502, e.code)
    }

    @Test
    fun a401WhoseRenewalIsRefusedIsAnAuthFailureAndDropsTheRefreshToken() = runBlocking {
        routes["/id/tokens"] = { json(401, """{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"revoked"}}""") }
        routes["/v1/"] = { json(401, """{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"expired"}}""") }
        val client = client()
        assertThrows(AdukiException.Auth::class.java) { runBlocking { client.scheduling.services() } }
        assertEquals("", client.session.tokens.value?.refresh)
        assertFalse(client.refresh())
    }

    @Test
    fun aRenewalAnsweredWithAnHtmlPageKeepsTheRefreshToken() = runBlocking {
        routes["/id/tokens"] = { MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>") }
        val client = client()
        assertFalse(client.refresh())
        assertEquals("rt_1", client.session.tokens.value?.refresh)
    }

    // A storage that fails like a broken local database.
    private class Broken : Storage {
        override fun getMessage(hex: String): Message? = null
        override fun putMessage(msg: Message) {}
        override fun getOutbox(id: Long): Outbox? = null
        override fun putOutbox(entry: Outbox): Long = 1
        override fun removeOutbox(id: Long) {}
        override fun pending(): List<Outbox> = throw UnsupportedOperationException("database closed")
        override fun failed(): List<Outbox> = emptyList()
        override fun size(): Int = 0
        override fun <T> tx(block: () -> T): T = block()
    }

    @Test
    fun aFailingBackgroundFlushIsReportedNotThrown() {
        val manager = Manager(Broken())
        val client = Aduki.builder().key("hm_test").manager(manager).worker(Worker(manager, { })).build()
        assertNull(client.fault.value)
        client.pause()
        client.resume() // flushes the outbox in the background
        val deadline = System.currentTimeMillis() + 5_000
        while (client.fault.value == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val fault = client.fault.value
        assertTrue("got $fault", fault is AdukiException.Protocol)
        assertTrue(causes(fault!!).any { it is UnsupportedOperationException })
        assertTrue("uncaught ${uncaught.map { it.toString() + it.stackTrace.take(8).joinToString() }}", uncaught.isEmpty())
        client.close()
    }

    @Test
    fun aFailingPublicFlushThrowsAnAdukiException() {
        val manager = Manager(Broken())
        val client = Aduki.builder().key("hm_test").manager(manager).worker(Worker(manager, { })).build()
        assertThrows(AdukiException.Protocol::class.java) { runBlocking { client.sync.flush() } }
        client.close()
    }

    @Test
    fun theRightsStreamSurvivesRefusalsAndStops() {
        var hits = 0
        routes["/jmap/eventsource"] = { hits++; MockResponse().setResponseCode(500).setBody("<html>oops</html>") }
        val client = client()
        client.watchRights(server.url("").toString())
        val deadline = System.currentTimeMillis() + 3_000
        while (hits == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        client.unwatchRights()
        assertTrue(hits >= 1)
        assertTrue("uncaught ${uncaught.map { it.toString() + it.stackTrace.take(8).joinToString() }}", uncaught.isEmpty())
        client.close()
    }
}
