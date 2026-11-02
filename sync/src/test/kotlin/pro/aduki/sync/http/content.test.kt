package pro.aduki.sync.http

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
import org.junit.Before
import org.junit.Test
import pro.aduki.core.models.BodyMode
import pro.aduki.net.http.Jmap

/** `JmapContentTransport`: `Email/get` with and without the reading capability. */
class ContentTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // The session (with [capabilities]) then one Email/get reply with [email].
    private fun jmap(capabilities: String, email: String): Jmap {
        val base = server.url("").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody(
            """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc"},"capabilities":$capabilities,
               "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
               "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?type={type}"}"""
        ))
        server.enqueue(MockResponse().setBody("""{"methodResponses":[["Email/get",{"accountId":"acc","list":[$email],"notFound":[]},"0"]]}"""))
        return Jmap(OkHttpClient(), base)
    }

    // The Email/get request: its `using` and arguments.
    private fun request(): Pair<List<Any>, JSONObject> {
        server.takeRequest() // session
        val body = JSONObject(server.takeRequest().body.readUtf8())
        return body.getJSONArray("using").toList() to body.getJSONArray("methodCalls").getJSONArray(0).getJSONObject(1)
    }

    private val reading = """{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{},"urn:aduki:params:jmap:reading":{"modes":["rich","plain","both"]}}"""

    private val attachment = """{"blobId":"b1","name":"a.pdf","type":"application/pdf","size":3,"cid":null,"disposition":"attachment"}"""

    @Test
    fun withoutTheCapabilityTheRawPartsAreFetched() = runBlocking {
        val client = jmap(
            """{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}}""",
            """{"id":"m1","textBody":[{"partId":"1","type":"text/plain"}],"htmlBody":[{"partId":"2","type":"text/html"}],
               "bodyValues":{"1":{"value":"hi\r\nthere"},"2":{"value":"<p onclick=x>hi</p>"}},"attachments":[$attachment]}"""
        )
        val body = JmapContentTransport(client).fetch("m1", BodyMode.Rich)
        val (using, args) = request()
        assertEquals(listOf(Jmap.CORE, Jmap.MAIL), using)
        assertFalse(args.has(JmapContentTransport.MODE))
        assertTrue(args.getBoolean("fetchTextBodyValues"))
        assertTrue(args.getBoolean("fetchHTMLBodyValues"))
        assertEquals("hi\r\nthere", body.text)
        assertEquals("<p onclick=x>hi</p>", body.html)
        assertFalse(body.sanitised)
        assertTrue(body.hasText)
        assertTrue(body.hasHtml)
        assertNull(body.quoteStart)
        assertEquals("b1", body.attachments.single().blob)
        assertFalse(JmapContentTransport(client).sanitised())
    }

    @Test
    fun withTheCapabilityTheModeIsSentAndRead() = runBlocking {
        val text = "Sounds good.\n\n> On Monday Ada wrote:\n> long flowed line\n"
        val client = jmap(
            reading,
            JSONObject()
                .put("id", "m1")
                .put("attachments", org.json.JSONArray().put(JSONObject(attachment)))
                .put("aduki:text", text)
                .put("aduki:quoteStart", text.indexOf("> On"))
                .put("aduki:hasText", true)
                .put("aduki:hasHtml", false)
                .toString()
        )
        val body = JmapContentTransport(client).fetch("m1", BodyMode.Plain)
        val (using, args) = request()
        assertTrue(Jmap.READING in using)
        assertTrue(Jmap.MAIL in using)
        assertEquals("plain", args.getString(JmapContentTransport.MODE))
        assertFalse(args.has("fetchTextBodyValues"))
        val properties = args.getJSONArray("properties").toList()
        assertTrue(properties.none { it.toString().startsWith("aduki:") })
        assertEquals(text, body.text)
        assertEquals("", body.html)
        assertEquals(14, body.quoteStart)
        assertTrue(body.text.substring(body.quoteStart!!).startsWith("> On Monday"))
        assertTrue(body.hasText)
        assertFalse(body.hasHtml)
        assertTrue(body.sanitised)
        assertEquals("b1", body.attachments.single().blob)
        assertTrue(JmapContentTransport(client).sanitised())
    }

    @Test
    fun everyModeHasItsWireName() {
        assertEquals("rich", JmapContentTransport.wire(BodyMode.Rich))
        assertEquals("plain", JmapContentTransport.wire(BodyMode.Plain))
        assertEquals("both", JmapContentTransport.wire(BodyMode.Both))
    }

    @Test
    fun bothReadsHtmlTextAndANullQuoteStart() {
        val body = JmapContentTransport.read(
            "m1",
            JSONObject("""{"id":"m1","aduki:html":"<p>hi</p>","aduki:text":"hi\n","aduki:quoteStart":null,"aduki:hasHtml":true,"aduki:hasText":true}""")
        )
        assertEquals("<p>hi</p>", body.html)
        assertEquals("hi\n", body.text)
        assertNull(body.quoteStart)
        assertTrue(body.hasHtml && body.hasText && body.sanitised)
        assertTrue(body.attachments.isEmpty())
    }

    @Test
    fun richWithoutARealHtmlPartIsEmpty() {
        val body = JmapContentTransport.read(
            "m1",
            JSONObject("""{"id":"m1","aduki:html":"","aduki:hasHtml":false,"aduki:hasText":true}""")
        )
        assertEquals("", body.html)
        assertEquals("", body.text)
        assertFalse(body.hasHtml)
        assertTrue(body.hasText)
        assertNull(body.quoteStart)
    }

    @Test
    fun unreadableBytesAreEmptyWithoutParts() {
        val body = JmapContentTransport.read(
            "m1",
            JSONObject("""{"id":"m1","aduki:html":null,"aduki:text":null,"aduki:quoteStart":null,"aduki:hasHtml":false,"aduki:hasText":false}""")
        )
        assertEquals("", body.html)
        assertEquals("", body.text)
        assertNull(body.quoteStart)
        assertFalse(body.hasHtml)
        assertFalse(body.hasText)
        assertTrue(body.sanitised)
    }
}
