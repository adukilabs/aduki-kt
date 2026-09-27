package pro.aduki.hermes.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.hermes.core.errors.HermesException

class LoginTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun identity() = server.url("/v1").toString()

    @Test
    fun testSubmitSignsInAtAdukiIdForMail() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"data":{"session":"00000000000000ab","access":"eyJ.mail","refresh":"rt_1","expires":600}}"""
            )
        )

        val tokens = Login.submit(client, identity(), "ada@aduki.me", "password123", code = "123456")

        assertEquals("eyJ.mail", tokens.token)
        assertEquals("rt_1", tokens.refresh)
        assertEquals("600", tokens.expires)
        assertEquals("00000000000000ab", tokens.session)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sessions", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("ada@aduki.me", body.getString("handle"))
        assertEquals("password123", body.getString("password"))
        assertEquals("123456", body.getString("code"))
        assertEquals("mail", body.getString("audience"))
        assertFalse(body.has("backup"))
    }

    @Test
    fun testSubmitWithBackupCode() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"data":{"session":"01","access":"a","refresh":"r","expires":600}}"""
            )
        )

        Login.submit(client, identity(), "ada@aduki.me", "pw", backup = "abcd-efgh")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("abcd-efgh", body.getString("backup"))
        assertFalse(body.has("code"))
    }

    @Test
    fun testSubmitUnauthorizedCarriesKind() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"success":false,"error":{"status":401,"kind":"auth.factor","message":"second factor required"}}"""
            )
        )

        val error = assertThrows(HermesException.Unauthorized::class.java) {
            Login.submit(client, identity(), "ada@aduki.me", "pw")
        }
        assertTrue(error.message!!.contains("auth.factor"))
    }

    @Test
    fun testSubmitRateLimitedIsNetworkError() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"success":false,"error":{"status":429,"kind":"rate.limited","message":"slow down"}}"""))

        val error = assertThrows(HermesException.Network::class.java) {
            Login.submit(client, identity(), "ada@aduki.me", "pw", code = "123456")
        }
        assertEquals(429, error.code)
    }

    @Test
    fun testRefreshRotates() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.new","refresh":"rt_2","expires":600}}"""
            )
        )

        val tokens = Login.refresh(client, identity(), "rt_1")

        assertEquals("eyJ.new", tokens.token)
        assertEquals("rt_2", tokens.refresh)

        val recorded = server.takeRequest()
        assertEquals("/v1/tokens", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("rt_1", body.getString("refresh"))
        assertEquals("mail", body.getString("audience"))
    }

    @Test
    fun testLogoutRevokesTheSessionWithAnIdToken() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.id","refresh":"rt_3","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(Login.logout(client, identity(), "00000000000000ab", "rt_2"))

        val swap = server.takeRequest()
        assertEquals("/v1/tokens", swap.path)
        val body = JSONObject(swap.body.readUtf8())
        assertEquals("rt_2", body.getString("refresh"))
        assertEquals("id", body.getString("audience"))

        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/v1/sessions/00000000000000ab", revoke.path)
        assertEquals("Bearer eyJ.id", revoke.getHeader("Authorization"))
    }

    @Test
    fun testLogoutWithoutSessionIsFalse() {
        assertFalse(Login.logout(client, identity(), "", "rt"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testLogoutWithSpentRefreshIsFalse() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"invalid"}}"""))

        assertFalse(Login.logout(client, identity(), "01", "rt_spent"))
    }
}
