package pro.aduki.hermes.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.hermes.core.errors.HermesException

class WhoamiTest {

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

    @Test
    fun testSuccessfulResolution() {
        val json = """
            {
                "hex": "usr_abc123",
                "tenant": "ten_xyz789",
                "email": "ada@aduki.me",
                "owner": true,
                "tier": "enterprise"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val client = OkHttpClient()
        val whoami = Whoami(client, server.url("/").toString())
        val identity = whoami.resolve()

        assertEquals("usr_abc123", identity.user)
        assertEquals("ten_xyz789", identity.tenant)
        assertTrue(identity.owner)
        assertEquals("enterprise", identity.tier)
        assertEquals("/user", server.takeRequest().path)
    }

    @Test
    fun testUnauthorizedThrowsAuthException() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val client = OkHttpClient()
        val whoami = Whoami(client, server.url("/").toString())

        assertThrows(HermesException.Auth::class.java) {
            whoami.resolve()
        }
    }

    @Test
    fun testScopesParsedCorrectly() {
        val json = """
            {
                "user": "usr_test",
                "tenant": "ten_test",
                "scopes": ["mail:read", "mail:write", "contacts:read"]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val client = OkHttpClient()
        val whoami = Whoami(client, server.url("/").toString())
        val identity = whoami.resolve()

        assertEquals(listOf("mail:read", "mail:write", "contacts:read"), identity.scopes)
    }
}

