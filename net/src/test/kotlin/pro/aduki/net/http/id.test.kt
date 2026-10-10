package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
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
import pro.aduki.core.errors.AdukiException

class IdTest {
    private lateinit var id: MockWebServer
    private lateinit var api: MockWebServer
    private var now = 1_000_000L
    private lateinit var subject: Id

    @Before
    fun setup() {
        id = MockWebServer().also { it.start() }
        api = MockWebServer().also { it.start() }
        subject = Id(OkHttpClient(), id.url("/v1").toString()) { now }
    }

    @After
    fun teardown() {
        id.shutdown()
        api.shutdown()
    }

    private fun ok(access: String, refresh: String, session: String? = null, expires: Int = 600) =
        MockResponse().setResponseCode(if (session != null) 201 else 200).setBody(
            """{"success":true,"data":{${if (session != null) "\"session\":\"$session\"," else ""}"access":"$access","refresh":"$refresh","expires":$expires}}"""
        )

    private fun signIn() {
        id.enqueue(ok("mail1", "r1", "ab"))
        subject.signIn("ada@aduki.me", "pw", code = "123456")
        id.takeRequest()
    }

    private fun get(audience: String = "mail") = subject.call(audience) { token ->
        Request.Builder().url(api.url("/x")).header("Authorization", "Bearer $token").build()
    }

    @Test
    fun signInCachesTokenAndSession() {
        signIn()
        assertEquals("mail1", subject.token("mail"))
        assertEquals("ab", subject.session())
        assertEquals(0, id.requestCount - 1)
    }

    @Test
    fun audiencesAreCachedSeparately() {
        signIn()
        id.enqueue(ok("space1", "r2"))
        assertEquals("space1", subject.token("space"))
        val req = id.takeRequest()
        assertEquals("/v1/tokens", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("r1", body.getString("refresh"))
        assertEquals("space", body.getString("audience"))
        // both cached now, no further calls
        assertEquals("space1", subject.token("space"))
        assertEquals("mail1", subject.token("mail"))
        assertEquals(2, id.requestCount)
        assertEquals("r2", subject.refreshToken())
    }

    @Test
    fun expiredTokenIsRenewedWithRotatedRefresh() {
        signIn()
        now += 600_000
        id.enqueue(ok("mail2", "r2"))
        assertEquals("mail2", subject.token())
        val body = JSONObject(id.takeRequest().body.readUtf8())
        assertEquals("r1", body.getString("refresh"))
        assertEquals("r2", subject.refreshToken())
    }

    @Test
    fun a401RenewsOnceAndRetries() {
        signIn()
        api.enqueue(MockResponse().setResponseCode(401))
        api.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        id.enqueue(ok("mail2", "r2"))
        get().use { assertEquals(200, it.code) }
        assertEquals("Bearer mail1", api.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer mail2", api.takeRequest().getHeader("Authorization"))
        assertEquals(2, id.requestCount)
    }

    @Test
    fun aSecond401IsReturnedNotLooped() {
        signIn()
        api.enqueue(MockResponse().setResponseCode(401))
        api.enqueue(MockResponse().setResponseCode(401))
        id.enqueue(ok("mail2", "r2"))
        get().use { assertEquals(401, it.code) }
        assertEquals(2, api.requestCount)
        assertEquals(2, id.requestCount)
    }

    @Test
    fun spentRefreshIsNeverReused() {
        signIn()
        now += 600_000
        id.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"success":false,"error":{"status":401,"kind":"auth.refresh","message":"spent"}}"""
            )
        )
        assertThrows(AdukiException.Unauthorized::class.java) { subject.token() }
        assertFalse(subject.signedIn())
        assertThrows(AdukiException.Unauthorized::class.java) { subject.token() }
        assertEquals(2, id.requestCount) // sign-in + the one refusal; no second send
    }

    @Test
    fun a2xxWithoutAccessKeepsTheRotatedRefresh() {
        signIn()
        now += 600_000
        id.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":{"refresh":"r9","expires":600}}"""))
        assertThrows(AdukiException.Auth::class.java) { subject.token() }
        assertEquals("r9", subject.refreshToken())
    }

    @Test
    fun serverErrorKeepsRefreshForRetry() {
        signIn()
        now += 600_000
        id.enqueue(MockResponse().setResponseCode(503))
        assertThrows(AdukiException.Network::class.java) { subject.token() }
        assertEquals("r1", subject.refreshToken())
    }

    @Test
    fun signOutRevokesWithIdAudienceAndForgets() {
        signIn()
        id.enqueue(ok("id1", "r2"))
        id.enqueue(MockResponse().setResponseCode(204))
        assertTrue(subject.signOut())
        assertEquals("id", JSONObject(id.takeRequest().body.readUtf8()).getString("audience"))
        val del = id.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/v1/sessions/ab", del.path)
        assertEquals("Bearer id1", del.getHeader("Authorization"))
        assertFalse(subject.signedIn())
    }

    @Test
    fun signOutFailureKeepsRotatedRefresh() {
        signIn()
        id.enqueue(ok("id1", "r2"))
        id.enqueue(MockResponse().setResponseCode(500))
        assertFalse(subject.signOut())
        assertEquals("r2", subject.refreshToken())
    }

    @Test
    fun adoptRestoresASignIn() {
        subject.adopt(pro.aduki.core.models.Tokens("t", "rr", "600", "cd"))
        assertEquals("t", subject.token())
        assertEquals("cd", subject.session())
        assertEquals(0, id.requestCount)
    }
}
