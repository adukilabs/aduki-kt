package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import java.util.concurrent.CopyOnWriteArrayList

class JmapTest {

    private lateinit var server: MockWebServer
    private lateinit var jmap: Jmap
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val base = server.url("/").toString().trimEnd('/')
                val path = request.path.orEmpty()
                return when {
                    path == "/jmap/session" -> MockResponse().setBody(
                        """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},
                           "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
                           "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
                           "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
                    )
                    path == "/jmap" && "Bad/get" in request.body.peek().readUtf8() -> MockResponse().setBody(
                        """{"methodResponses":[["error",{"type":"unknownMethod"},"0"]],"sessionState":"s"}"""
                    )
                    path == "/jmap" -> MockResponse().setBody(
                        """{"methodResponses":[["Email/get",{"list":[{"id":"m1"}],"state":"9"},"0"]],"sessionState":"s"}"""
                    )
                    path == "/jmap/upload/acc1" -> MockResponse().setBody("""{"accountId":"acc1","blobId":"b1","type":"text/plain","size":5}""")
                    path.startsWith("/jmap/download/acc1/b1/a%20b.txt") -> MockResponse().setBody("hello")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        jmap = Jmap(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun theSessionIsFetchedOnce() {
        assertEquals("acc1", jmap.account())
        jmap.account()
        assertEquals(1, requests.count { it.path == "/jmap/session" })
        assertTrue(Jmap.MAIL in jmap.session().capabilities)
    }

    @Test
    fun aCallCarriesUsingAndTheAccount() {
        val reply = jmap.call("Email/get", JSONObject().put("ids", JSONArray(listOf("m1"))))
        assertEquals("m1", reply.getJSONArray("list").getJSONObject(0).getString("id"))
        val sent = JSONObject(requests.last { it.path == "/jmap" }.body.readUtf8())
        assertEquals("urn:ietf:params:jmap:core", sent.getJSONArray("using").getString(0))
        val call = sent.getJSONArray("methodCalls").getJSONArray(0)
        assertEquals("Email/get", call.getString(0))
        assertEquals("acc1", call.getJSONObject(1).getString("accountId"))
    }

    @Test
    fun aMethodErrorIsThrownWithItsType() {
        val e = assertThrows(AdukiException.Api::class.java) { jmap.call("Bad/get", JSONObject()) }
        assertEquals("unknownMethod", e.kind)
    }

    @Test
    fun uploadAndDownloadUseTheSessionTemplates() {
        val blob = jmap.upload("hello".toByteArray(), "text/plain")
        assertEquals(Jmap.Blob("b1", "text/plain", 5), blob)
        val text = jmap.download("b1", "a b.txt", "text/plain").use { String(it.readBytes()) }
        assertEquals("hello", text)
        assertTrue(requests.any { it.path == "/jmap/download/acc1/b1/a%20b.txt?accept=text%2Fplain" })
    }

    @Test
    fun aRefusalIsAnAuthError() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        assertThrows(AdukiException.Auth::class.java) { jmap.session() }
    }
}
