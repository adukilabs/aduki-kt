package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AuthTest {

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
    fun testHeader() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val apiKey = "hm_live_abc123xyz789"
        val client = OkHttpClient.Builder()
            .addInterceptor(Auth(apiKey, apiKey = true))
            .build()

        val request = Request.Builder()
            .url(server.url("/user"))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        val recorded = server.takeRequest()
        assertEquals("Key $apiKey", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals("Aduki-Android/1.0.0", recorded.getHeader("User-Agent"))
    }

    @Test
    fun theSchemeFollowsTheFlagNotThePrefix() {
        assertEquals("Key hm_abc", Scheme.header("hm_abc", apiKey = true))
        assertEquals("Key tok", Scheme.header(" tok ", apiKey = true))
        assertEquals("Bearer hm_abc", Scheme.header("hm_abc"))
        assertEquals("Bearer key_abc", Scheme.header("key_abc"))
        assertEquals("Bearer eyJ.x.y", Scheme.header("eyJ.x.y"))
        assertEquals("", Scheme.header("  ", apiKey = true))
    }

    @Test
    fun aKeyShapedValueIsBearerUnlessMarkedAsAKey() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder().addInterceptor(Auth("hm_looks_like_a_key")).build()
        client.newCall(Request.Builder().url(server.url("/user")).build()).execute().close()
        assertEquals("Bearer hm_looks_like_a_key", server.takeRequest().getHeader("Authorization"))
    }
}
