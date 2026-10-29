package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.models.TokenStore
import pro.aduki.core.models.Tokens

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

    private fun client(http: OkHttpClient? = null): Aduki {
        val builder = Aduki.builder()
        if (http != null) builder.http(http)
        val client = builder
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

    @Test
    fun failedRevocationKeepsTheRotatedRefreshForARetry() = runBlocking {
        val client = client()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.id","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(503))

        assertFalse(client.logout())
        assertEquals("rt_2", client.session.refresh())
    }

    @Test
    fun customClientRenewsAndKeepsMailCredentialsOffIdentityCalls() = runBlocking {
        // A custom client whose interceptor always adds the mail token.
        val custom = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer eyJ.old").build())
            }
            .build()
        val client = client(custom)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.new","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hex":"u1","tenant":"t1"}"""))

        assertNotNull(client.me())
        server.takeRequest()
        assertNull("identity calls carry no mail token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer eyJ.new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun refusedRefreshIsDroppedAndNeverSentAgain() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setResponseCode(401)) // mail refuses the token
        server.enqueue(MockResponse().setResponseCode(401)) // Aduki ID refuses the refresh
        assertNull(client.me())
        assertEquals("", client.session.refresh())
        val before = server.requestCount

        assertFalse(client.refresh())
        assertEquals("a spent refresh token is not presented again", before, server.requestCount)
    }

    @Test
    fun renewalGoesThroughTheIdClientAndKeepsLifetime() = runBlocking {
        val client = client()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.new","refresh":"rt_2","expires":600}}"""
            )
        )
        assertTrue(client.refresh())
        assertEquals("600", client.session.tokens.value!!.expires)
        // A sign-in replaced from outside is picked up by the next renewal.
        client.session.update(Tokens(token = "eyJ.x", refresh = "rt_9", expires = "600", session = "00000000000000cd"))
        server.takeRequest()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.y","refresh":"rt_10","expires":600}}"""
            )
        )
        assertTrue(client.refresh())
        assertEquals("rt_9", JSONObject(server.takeRequest().body.readUtf8()).getString("refresh"))
    }

    @Test
    fun watchRightsRenewsOnTheStreamEventAndStopsOnLogout() = runBlocking {
        val client = client()
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("event: rights\ndata: {\"@type\":\"Rights\",\"epoch\":2}\n\n")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.new","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(": ping\n\n"))

        client.watchRights(server.url("/").toString())

        val stream = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/jmap/eventsource?types=*&ping=30", stream.path)
        assertEquals("Bearer eyJ.old", stream.getHeader("Authorization"))
        val renew = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/id/tokens", renew.path)
        assertEquals("rt_1", JSONObject(renew.body.readUtf8()).getString("refresh"))
        val reconnect = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("the reconnect carries the new token", "Bearer eyJ.new", reconnect.getHeader("Authorization"))
        assertEquals("eyJ.new", client.session.token())
        client.unwatchRights()
    }

    private fun boundToken(key: pro.aduki.crypto.dpop.Key, tag: String): String {
        val body = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"tag":"$tag","cnf":{"jkt":"${key.thumbprint()}"}}""".toByteArray())
        return "h.$body.s"
    }

    @Test
    fun dpopBoundClientSendsProofsAndResignsTheRenewedRequest() = runBlocking {
        val key = pro.aduki.crypto.dpop.Software.p256()
        val old = boundToken(key, "old")
        val fresh = boundToken(key, "new")
        val client = Aduki.builder().dpop(key).token(old)
            .endpoint(server.url("/v1").toString()).identity(server.url("/id").toString()).build()
        client.session.update(Tokens(token = old, refresh = "rt_1", expires = "600", session = "00000000000000ab"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"$fresh","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hex":"u1","tenant":"t1"}"""))

        assertNotNull(client.me())

        val first = server.takeRequest()
        assertEquals("DPoP $old", first.getHeader("Authorization"))
        assertNotNull(first.getHeader("DPoP"))
        val renew = server.takeRequest()
        assertNotNull("the refresh carries a proof", renew.getHeader("DPoP"))
        assertNull(renew.getHeader("Authorization"))
        val retry = server.takeRequest()
        assertEquals("DPoP $fresh", retry.getHeader("Authorization"))
        assertNotNull(retry.getHeader("DPoP"))
        assertTrue(retry.getHeader("DPoP") != first.getHeader("DPoP"))
    }

    @Test
    fun twoClientsSharingTokenStoreRecoverWhenFirstRotatesTokens() = runBlocking {
        val store = object : TokenStore {
            var current: Tokens? = Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "sess_1")
            override fun load(): Tokens? = current
            override fun save(tokens: Tokens) { current = tokens }
            override fun clear() { current = null }
        }

        val clientA = Aduki.builder()
            .tokens(store)
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .build()

        val clientB = Aduki.builder()
            .tokens(store)
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .build()

        // Client A makes a request, gets 401, renews rt_1 -> rt_2, receives eyJ.newA
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.newA","refresh":"rt_2","expires":600}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hex":"u1","tenant":"t1"}"""))

        val idA = clientA.me()
        assertNotNull(idA)
        assertEquals("u1", idA!!.user)
        assertEquals("eyJ.newA", store.load()?.token)
        assertEquals("rt_2", store.load()?.refresh)

        // Client B still had eyJ.old in memory. When it requests /v1/user/me:
        // 1) First call uses eyJ.old and gets 401
        // 2) Client B renews, reloads from tokenStore, sees fresh eyJ.newA
        // 3) Re-attempts request with eyJ.newA without rotating again
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hex":"u1","tenant":"t1"}"""))

        val idB = clientB.me()
        assertNotNull(idB)
        assertEquals("u1", idB!!.user)
        assertEquals("eyJ.newA", clientB.session.token())
        assertEquals("rt_2", clientB.session.refresh())
    }

    @Test
    fun sharedTokenStoreRecoversWhenRefreshSpentElsewhereReturns401() = runBlocking {
        var loadCount = 0
        val store = object : TokenStore {
            var current: Tokens? = Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "sess_1")
            override fun load(): Tokens? {
                loadCount++
                if (loadCount >= 4 && current?.refresh == "rt_1") {
                    current = Tokens(token = "eyJ.old", refresh = "rt_2", expires = "600", session = "sess_1")
                }
                return current
            }
            override fun save(tokens: Tokens) { current = tokens }
            override fun clear() { current = null }
        }

        val client = Aduki.builder()
            .tokens(store)
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .build()

        // 1. Mail rejects stale access token
        server.enqueue(MockResponse().setResponseCode(401))
        // 2. Aduki ID rejects rt_1 because it was already spent
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"token_revoked"}"""))
        // 3. Client catches 401, re-checks store, sees rt_2, rotates rt_2 -> rt_3
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.new3","refresh":"rt_3","expires":600}}"""
            )
        )
        // 4. Retried mail request succeeds with eyJ.new3
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hex":"u1","tenant":"t1"}"""))

        val id = client.me()
        assertNotNull(id)
        assertEquals("u1", id!!.user)
        assertEquals("eyJ.new3", client.session.token())
        assertEquals("rt_3", client.session.refresh())
        assertEquals("rt_3", store.load()?.refresh)
    }

    @Test
    fun revocationClearsBothMemoryAndTokenStore() = runBlocking {
        val store = object : TokenStore {
            var current: Tokens? = Tokens(token = "eyJ.bad", refresh = "rt_revoked", expires = "600", session = "sess_1")
            override fun load(): Tokens? = current
            override fun save(tokens: Tokens) { current = tokens }
            override fun clear() { current = null }
        }

        val client = Aduki.builder()
            .tokens(store)
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .build()

        // Mail rejects token
        server.enqueue(MockResponse().setResponseCode(401))
        // Aduki ID rejects refresh token
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"unauthorized"}"""))

        val id = client.me()
        assertNull(id)
        assertEquals("", client.session.refresh())
        assertNull(store.load())
    }

    @Test
    fun listSessionsReturnsParsedSessions() = runBlocking {
        val client = client()
        // 1. Tokens renewal for audience 'id'
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.id","refresh":"rt_2","expires":600}}"""
            )
        )
        // 2. GET /id/sessions response
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":[{"session":"s1","device":"Phone","tier":2,"bound":true,"created":"2026-10-29T06:00:00Z","seen":"2026-10-29T06:30:00Z","current":true},{"session":"s2","device":null,"tier":1,"bound":false,"created":"2026-10-28T12:00:00Z","seen":"2026-10-28T12:00:00Z","current":false}]}"""
            )
        )

        val sessions = client.sessions()
        assertEquals(2, sessions.size)
        assertEquals("s1", sessions[0].id)
        assertEquals("Phone", sessions[0].device)
        assertTrue(sessions[0].bound)
        assertTrue(sessions[0].current)
        assertEquals("s2", sessions[1].id)
        assertFalse(sessions[1].current)

        val reqRenew = server.takeRequest()
        assertEquals("/id/tokens", reqRenew.path)
        val reqSessions = server.takeRequest()
        assertEquals("/id/sessions", reqSessions.path)
        assertEquals("Bearer eyJ.id", reqSessions.getHeader("Authorization"))
    }

    @Test
    fun revokeSessionDeletesAndCurrentSessionLogsOut() = runBlocking {
        val client = client()
        // 1. Tokens renewal for audience 'id'
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.id","refresh":"rt_2","expires":600}}"""
            )
        )
        // 2. DELETE /id/sessions/s2 (another session)
        server.enqueue(MockResponse().setResponseCode(204))

        val revoked = client.revoke("s2")
        assertTrue(revoked)
        // Current session is still active
        assertEquals("00000000000000ab", client.session.tokens.value?.session)

        server.takeRequest() // renew
        val delReq = server.takeRequest()
        assertEquals("DELETE", delReq.method)
        assertEquals("/id/sessions/s2", delReq.path)

        // Now revoke current session "00000000000000ab" -> triggers local cleanup
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"access":"eyJ.id2","refresh":"rt_3","expires":600}}"""
            )
        ) // POST /id/tokens for 'id' audience
        server.enqueue(MockResponse().setResponseCode(204)) // DELETE /id/sessions/00000000000000ab

        val selfRevoked = client.revoke("00000000000000ab")
        assertTrue(selfRevoked)
        assertNull(client.session.tokens.value)
    }
}
