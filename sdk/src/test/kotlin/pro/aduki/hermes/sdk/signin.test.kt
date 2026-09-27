package pro.aduki.hermes.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.hermes.core.models.Tokens

/** Aduki ID sign-in wiring: renewal on 401 and sign-out. */
class SigninTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun client(): HermesClient {
        val client = HermesClient.builder()
            .token("eyJ.old")
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .build()
        client.session.update(Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "00000000000000ab"))
        return client
    }

    @Test
    fun expiredTokenIsRenewedOnceAndRetried() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.new","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hex":"u1","tenant":"t1"}"""))

        val identity = client.me()

        assertNotNull(identity)
        assertEquals("u1", identity!!.user)
        assertEquals("Bearer eyJ.old", server.takeRequest().getHeader("Authorization"))
        val renew = server.takeRequest()
        assertEquals("/id/tokens", renew.path)
        assertEquals("rt_1", JSONObject(renew.body.readUtf8()).getString("refresh"))
        assertEquals("Bearer eyJ.new", server.takeRequest().getHeader("Authorization"))

        val tokens = client.session.tokens.value!!
        assertEquals("eyJ.new", tokens.token)
        assertEquals("rt_2", tokens.refresh)
        assertEquals("the session hex survives renewal", "00000000000000ab", tokens.session)
    }

    @Test
    fun logoutRevokesTheSessionAndClearsTokens() = runBlocking {
        val client = client()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.id","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(client.logout())

        assertEquals("/id/tokens", server.takeRequest().path)
        val revoke = server.takeRequest()
        assertEquals("/id/sessions/00000000000000ab", revoke.path)
        assertEquals("Bearer eyJ.id", revoke.getHeader("Authorization"))
        assertNull(client.session.tokens.value)
    }
}
