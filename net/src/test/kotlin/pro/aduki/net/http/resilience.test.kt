package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Key
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Malformed, partial and refused answers come back as AdukiException or
 * IOException, never as a raw runtime exception.
 */
class ResilienceTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun session() = MockResponse().setBody(
        """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
           "apiUrl":"${base()}/jmap","uploadUrl":"${base()}/jmap/upload/{accountId}",
           "downloadUrl":"${base()}/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
    )

    private fun html(code: Int) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "text/html")
        .setBody("<html><body><h1>$code Bad Gateway</h1></body></html>")

    @Test
    fun anHtmlPageWithA200IsAProtocolFailure() {
        server.enqueue(html(200))
        val e = assertThrows(AdukiException.Protocol::class.java) { Jmap(OkHttpClient(), base()).session() }
        assertTrue(e.message!!.contains("not JSON"))
    }

    @Test
    fun anHtml502IsANetworkFailureWithItsStatus() {
        server.enqueue(session())
        server.enqueue(html(502))
        val e = assertThrows(AdukiException.Network::class.java) {
            Jmap(OkHttpClient(), base()).call("Email/get", org.json.JSONObject())
        }
        assertEquals(502, e.code)
    }

    @Test
    fun anEmptyBodyIsAProtocolFailure() {
        server.enqueue(session())
        server.enqueue(MockResponse().setBody(""))
        assertThrows(AdukiException.Protocol::class.java) {
            Jmap(OkHttpClient(), base()).call("Email/get", org.json.JSONObject())
        }
    }

    @Test
    fun aMalformedMethodResponseIsAProtocolFailure() {
        server.enqueue(session())
        server.enqueue(MockResponse().setBody("""{"methodResponses":[["Email/get","not an object","0"]]}"""))
        assertThrows(AdukiException.Protocol::class.java) {
            Jmap(OkHttpClient(), base()).call("Email/get", org.json.JSONObject())
        }
    }

    @Test
    fun aReplyWithoutTheCallsResponseIsAProtocolFailure() {
        server.enqueue(session())
        server.enqueue(MockResponse().setBody("""{"methodResponses":[["Email/get",{},"other"]]}"""))
        assertThrows(AdukiException.Protocol::class.java) {
            Jmap(OkHttpClient(), base()).call("Email/get", org.json.JSONObject())
        }
    }

    @Test
    fun aSessionWithAnInvalidUrlIsAProtocolFailure() {
        server.enqueue(MockResponse().setBody(
            """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},"apiUrl":"not a url","uploadUrl":"x","downloadUrl":"y"}"""
        ))
        assertThrows(AdukiException.Protocol::class.java) {
            Jmap(OkHttpClient(), base()).call("Email/get", org.json.JSONObject())
        }
    }

    @Test
    fun aTimeoutIsANetworkFailureWithTheCause() {
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS))
        val http = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        val e = assertThrows(AdukiException.Network::class.java) { Jmap(http, base()).session() }
        assertTrue(e.cause is SocketTimeoutException)
        assertNull(e.code)
    }

    @Test
    fun aConnectionResetMidDownloadIsAnIOException() {
        server.enqueue(session())
        server.enqueue(
            MockResponse().setBody("x".repeat(64 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val stream = Jmap(OkHttpClient(), base()).download("b1", "a.bin")
        assertThrows(IOException::class.java) { stream.use { it.readBytes() } }
    }

    @Test
    fun a429CarriesRetryAfter() {
        server.enqueue(session())
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("slow down"))
        val e = assertThrows(AdukiException.Network::class.java) {
            Jmap(OkHttpClient(), base()).call("Email/get", org.json.JSONObject())
        }
        assertEquals(429, e.code)
        assertEquals(120L, e.retryAfter)
    }

    @Test
    fun restClientsCarryRetryAfterToo() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "7"))
        val e = assertThrows(AdukiException.Network::class.java) { Mail(OkHttpClient(), "${base()}/v1").mailboxes() }
        assertEquals(503, e.code)
        assertEquals(7L, e.retryAfter)
    }

    @Test
    fun retryAfterReadsSecondsAndHttpDates() {
        val now = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneOffset.UTC)
        fun after(value: String): Long? {
            server.enqueue(MockResponse().setHeader("Retry-After", value))
            return OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                Envelope.retryAfter(it) { now }
            }
        }
        assertEquals(30L, after("30"))
        assertEquals(90L, after("Mon, 2 Nov 2026 12:01:30 GMT"))
        assertEquals(0L, after("Mon, 2 Nov 2026 11:00:00 GMT"))
        assertNull(after("soon"))
    }

    @Test
    fun aSchedulingAnswerThatIsNotJsonIsAProtocolFailure() {
        server.enqueue(html(200))
        assertThrows(AdukiException.Protocol::class.java) { Scheduling(OkHttpClient(), "${base()}/v1").calendars() }
    }

    @Test
    fun aMailAnswerThatIsNotJsonIsAProtocolFailure() {
        server.enqueue(html(200))
        assertThrows(AdukiException.Protocol::class.java) { Mail(OkHttpClient(), "${base()}/v1").mailboxes() }
    }

    @Test
    fun aKeyThatCannotSignFailsTheCallWithAnIOException() {
        val broken = object : Key {
            override val alg = "ES256"
            override val jwk = mapOf("kty" to "EC", "crv" to "P-256", "x" to "x", "y" to "y")
            override fun sign(input: ByteArray): ByteArray = throw IllegalStateException("key invalidated")
        }
        val http = OkHttpClient.Builder().addInterceptor(Dpop(broken)).build()
        server.enqueue(MockResponse().setBody("{}"))
        val e = assertThrows(IOException::class.java) {
            http.newCall(Request.Builder().url(server.url("/v1/sessions")).build()).execute().close()
        }
        assertTrue(e.cause is IllegalStateException)
    }

    @Test
    fun aPushWithNonStringMailboxIdsIsRead() {
        val push = Push.parse(
            """{"@type":"StateChange","changed":{"a":{"Email":"1"}},"mailboxes":{"a":["m1",{"x":1},"m2"]}}"""
        ) as Push.Change
        assertTrue(push.mail())
    }
}
