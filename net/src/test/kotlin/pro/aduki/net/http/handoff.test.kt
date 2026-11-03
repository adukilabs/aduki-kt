package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Tokens

/** Handoffs: one app asks Aduki ID for a single-use code, another redeems it for its own session. */
class HandoffTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun identity() = server.url("/v1").toString()

    @Test
    fun aHandoffIsIssuedWithAnIdAudienceToken() {
        // the id audience token comes from a rotation of the session's refresh token
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"access":"eyJ.id","refresh":"rt_2","expires":600}}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"handoff":"adkh_abc","expires":1900000000}}"""))
        val id = Id(client, identity())
        id.adopt(Tokens("eyJ.mail", "rt_1", "600", "s1"))

        val handoff = id.handoff("pro.aduki.calendar", ttl = 600)

        assertEquals(Handoff("adkh_abc", 1_900_000_000), handoff)
        assertEquals("rt_2", id.refreshToken())
        val rotate = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("id", rotate.getString("audience"))
        val issue = server.takeRequest()
        assertEquals("/v1/handoffs", issue.path)
        assertEquals("Bearer eyJ.id", issue.getHeader("Authorization"))
        val body = JSONObject(issue.body.readUtf8())
        assertEquals("pro.aduki.calendar", body.getString("target"))
        assertEquals(600, body.getInt("ttl"))
    }

    @Test
    fun ttlAndTargetAreChecked() {
        val id = Id(client, identity())
        for ((target, ttl) in listOf("" to 600L, "pro.aduki.calendar" to 29L, "pro.aduki.calendar" to 86_401L)) {
            try {
                id.handoff(target, ttl)
                fail("expected IllegalArgumentException for $target/$ttl")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redeemingOpensANewSessionWithTheDeviceName() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"success":true,"data":{"session":"00000000000000cd","access":"eyJ.cal","refresh":"rt_cal","expires":600}}"""
        ))
        val tokens = Login.redeem(client, identity(), " adkh_abc ", device = "Calendar on Pixel")
        assertEquals(Tokens("eyJ.cal", "rt_cal", "600", "00000000000000cd"), tokens)
        val request = server.takeRequest()
        assertEquals("/v1/handoffs/redeem", request.path)
        assertEquals(null, request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("adkh_abc", body.getString("handoff"))
        assertEquals("mail", body.getString("audience"))
        assertEquals("Calendar on Pixel", body.getString("device"))
    }

    @Test
    fun aUsedOrExpiredHandoffIsUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(
            """{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"handoff is used or expired"}}"""
        ))
        try {
            Login.redeem(client, identity(), "adkh_used")
            fail("expected Unauthorized")
        } catch (e: AdukiException.Unauthorized) {
            assertTrue(e.message!!.contains("auth.invalid"))
        }
    }

    @Test
    fun expiryIsInSeconds() {
        val handoff = Handoff("adkh", 1_000)
        assertFalse(handoff.expired(now = 999))
        assertTrue(handoff.expired(now = 1_000))
        assertFalse("unknown expiry is not expired", Handoff("adkh", 0).expired(now = 5))
    }
}
