package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.models.TokenStore
import pro.aduki.core.models.Tokens
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every API client of [Aduki] renews an expired access token once on a 401
 * and retries (aduki-kt#110): mail, contacts, scheduling and JMAP all share
 * the one authenticated connection.
 */
class RenewalTest {

    private lateinit var server: MockWebServer
    private val renewals = AtomicInteger()
    private val presented = mutableListOf<String>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/id/tokens")) {
                    synchronized(presented) { presented += JSONObject(request.body.readUtf8()).getString("refresh") }
                    val n = renewals.incrementAndGet()
                    Thread.sleep(50) // let concurrent 401s pile up behind the renewal
                    return json(201, """{"success":true,"data":{"access":"eyJ.new","refresh":"rt_${n + 1}","expires":600}}""")
                }
                if (request.getHeader("Authorization") != "Bearer eyJ.new") {
                    return json(401, """{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"expired"}}""")
                }
                val base = server.url("").toString().trimEnd('/')
                return when {
                    path.startsWith("/jmap/session") -> json(
                        200,
                        """{"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc"},"capabilities":{},
                           "apiUrl":"$base/jmap","uploadUrl":"$base/u","downloadUrl":"$base/d"}"""
                    )
                    path.startsWith("/v1/user/contacts") -> json(200, """{"success":true,"data":{"items":[],"total":0,"next":null}}""")
                    path.startsWith("/v1/user/pages") -> json(200, """{"success":true,"data":[]}""")
                    path.startsWith("/v1/user/mailbox") -> json(200, """{"success":true,"data":{"items":[],"total":0}}""")
                    else -> json(200, """{"success":true,"data":[]}""")
                }
            }
        }
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private class Memory(var tokens: Tokens?) : TokenStore {
        override fun load() = tokens
        override fun save(tokens: Tokens) { this.tokens = tokens }
        override fun clear() { tokens = null }
    }

    private fun client(store: TokenStore? = null): Aduki {
        val builder = Aduki.builder()
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
        if (store != null) builder.tokens(store) else builder.token("eyJ.old")
        val client = builder.build()
        if (store == null) client.session.update(Tokens(token = "eyJ.old", refresh = "rt_1", expires = "600", session = "ab"))
        return client
    }

    @Test
    fun everyApiClientRenewsOnceAndRetries() = runBlocking {
        val calls: List<Pair<String, (Aduki) -> Unit>> = listOf(
            "contacts" to { c -> c.contactsApi.list() },
            "scheduling" to { c -> c.schedulingApi.services() },
            "mail" to { c -> c.mailApi.mailboxes() },
            "jmap" to { c -> c.jmap.session(refresh = true) }
        )
        for ((name, call) in calls) {
            renewals.set(0)
            val client = client()
            call(client)
            assertEquals("$name renews once", 1, renewals.get())
            assertEquals("$name keeps the rotated refresh token", "rt_2", client.session.tokens.value!!.refresh)
        }
    }

    @Test
    fun aStoredSessionWithoutAnAccessTokenRenewsBeforeTheFirstRequest() = runBlocking {
        val store = Memory(Tokens(token = "", refresh = "rt_1", expires = "", session = "ab"))
        val client = client(store)
        client.schedulingApi.services()
        assertEquals(1, renewals.get())
        assertEquals("rt_2", store.tokens!!.refresh)
    }

    @Test
    fun concurrent401sShareOneRenewal() {
        val client = client()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        val failures = AtomicInteger()
        repeat(8) { i ->
            pool.execute {
                try {
                    if (i % 2 == 0) client.contactsApi.list() else client.schedulingApi.services()
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
        assertEquals("one rotation for all eight", 1, renewals.get())
        assertEquals(listOf("rt_1"), presented)
    }
}
