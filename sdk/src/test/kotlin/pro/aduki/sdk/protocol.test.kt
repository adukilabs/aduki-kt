package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.models.TokenStore
import pro.aduki.core.models.Tokens
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renewal under the 0.8.0 rules: tokens are dropped only when Aduki ID says
 * the session ended, rotated tokens are saved with `saveNow`, and two
 * clients of one session in one process rotate it once.
 */
class ProtocolTest {

    private lateinit var server: MockWebServer
    private val rotations = AtomicInteger()
    private val presented = Collections.synchronizedList(mutableListOf<String>())
    private val deletes = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var refusal: String? = null

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/id/tokens")) {
                    val body = JSONObject(request.body.readUtf8())
                    presented += body.getString("refresh")
                    refusal?.let { return json(401, """{"success":false,"error":{"status":401,"kind":"$it","message":"m"}}""") }
                    val n = rotations.incrementAndGet()
                    Thread.sleep(50) // let the other client pile up behind the rotation
                    val access = if (body.optString("audience") == "id") "eyJ.id" else "eyJ.new"
                    return json(201, """{"success":true,"data":{"access":"$access","refresh":"rt_${n + 1}","expires":600}}""")
                }
                if (path.startsWith("/id/me")) {
                    return json(200, """{"success":true,"data":{"hex":"00a1","handle":"ada@aduki.me","name":"Ada","kind":"personal",
                        "tenant":null,"photo":null,"center":null,"unlock":"self","session":"ab","group":"g1"}}""")
                }
                if (path.startsWith("/id/sessions") && request.method == "DELETE") {
                    deletes += path
                    return json(200, """{"success":true,"data":{"ended":2}}""")
                }
                if (request.getHeader("Authorization") != "Bearer eyJ.new") {
                    return json(401, """{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"expired"}}""")
                }
                return json(200, """{"success":true,"data":[]}""")
            }
        }
        server.start()
    }

    @After
    fun teardown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    /** A store shared by two clients, counting synchronous saves. */
    private class Shared(@Volatile var tokens: Tokens?) : TokenStore {
        val now = AtomicInteger()
        val later = AtomicInteger()
        override fun load() = tokens
        override fun save(tokens: Tokens) { later.incrementAndGet(); this.tokens = tokens }
        override fun saveNow(tokens: Tokens) { now.incrementAndGet(); this.tokens = tokens }
        override fun clear() { tokens = null }
    }

    private fun client(store: TokenStore): Aduki = Aduki.builder()
        .endpoint(server.url("/v1").toString())
        .identity(server.url("/id").toString())
        .tokens(store)
        .build()

    @Test
    fun twoClientsOfOneSessionRotateItOnce() {
        val store = Shared(Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "ab"))
        val first = client(store)
        val second = client(store)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        val failures = AtomicInteger()
        repeat(8) { i ->
            pool.execute {
                try {
                    (if (i % 2 == 0) first else second).schedulingApi.services()
                } catch (_: Exception) {
                    failures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, failures.get())
        assertEquals("one rotation for both clients", 1, rotations.get())
        assertEquals("the spent token is never presented again", listOf("rt_1"), presented.toList())
        assertEquals("rt_2", store.tokens!!.refresh)
        assertEquals("saved synchronously", 1, store.now.get())
        assertEquals(0, store.later.get())
    }

    @Test
    fun aRefusedProofOrNonceKeepsTheSession() = runBlocking {
        for (kind in listOf("auth.dpop", "auth.nonce", "auth.factor")) {
            val store = Shared(Tokens(token = "", refresh = "rt_1", expires = "", session = "ab"))
            val client = client(store)
            refusal = kind
            assertFalse(client.refresh())
            assertEquals("$kind keeps the stored token", "rt_1", store.tokens?.refresh)
            assertEquals("$kind keeps the session", "rt_1", client.session.tokens.value?.refresh)
        }
        refusal = null
    }

    @Test
    fun anEndedSessionDropsTheTokens() = runBlocking {
        for (kind in listOf("auth.reused", "auth.revoked", "auth.expired", "auth.invalid")) {
            val store = Shared(Tokens(token = "", refresh = "rt_1", expires = "", session = "ab"))
            val client = client(store)
            refusal = kind
            assertFalse(client.refresh())
            assertNull("$kind clears the store", store.tokens)
        }
        refusal = null
    }

    @Test
    fun signingOutOfTheDeviceEndsTheGroupAndForgetsTheTokens() = runBlocking {
        val store = Shared(Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "ab"))
        val client = client(store)
        val me = client.account()!!
        assertEquals("00a1", me.hex)
        assertEquals("g1", me.group)
        assertTrue(client.endDevice())
        assertEquals(listOf("/id/sessions?group=g1"), deletes.toList())
        assertNull(store.tokens)
        assertNull(client.session.tokens.value?.refresh?.ifBlank { null })
    }
}
