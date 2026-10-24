package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.net.http.Condition
import pro.aduki.net.http.Push
import pro.aduki.net.http.Rule
import pro.aduki.net.http.Sieve
import pro.aduki.net.http.Subscription
import java.util.concurrent.CopyOnWriteArrayList

class SettingsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: Aduki
    private val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private val blobs = HashMap<String, String>()
    private var script: JSONObject? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                val path = request.path.orEmpty()
                return when {
                    path == "/jmap/session" -> MockResponse().setBody(
                        """{"capabilities":{},"primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
                           "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
                           "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
                    )
                    path == "/jmap/upload/acc1" -> {
                        val id = "b${blobs.size + 1}"
                        blobs[id] = request.body.readUtf8()
                        MockResponse().setBody("""{"blobId":"$id","type":"application/sieve","size":1}""")
                    }
                    path.startsWith("/jmap/download/acc1/") ->
                        MockResponse().setBody(blobs.getValue(path.removePrefix("/jmap/download/acc1/").substringBefore('/')))
                    path == "/jmap" -> {
                        val sent = JSONObject(request.body.readUtf8()).getJSONArray("methodCalls")
                        val out = JSONArray()
                        for (i in 0 until sent.length()) {
                            val call = sent.getJSONArray(i)
                            calls += call.getString(0) to call.getJSONObject(1)
                            out.put(JSONArray().put(call.getString(0)).put(reply(call.getString(0), call.getJSONObject(1))).put(call.getString(2)))
                        }
                        MockResponse().setBody(JSONObject().put("methodResponses", out).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = Aduki.builder().token("t").endpoint(server.url("/v1").toString()).build()
    }

    // A tiny server: one sieve script, two push subscriptions of which one is this device's.
    private fun reply(name: String, args: JSONObject): JSONObject = when (name) {
        "SieveScript/get" -> JSONObject().put("state", "V-1").put("list", JSONArray().apply { script?.let(::put) })
        "SieveScript/validate" -> JSONObject().put("error", JSONObject.NULL)
        "SieveScript/set" -> {
            val update = args.optJSONObject("update")
            if (update != null) script!!.put("blobId", update.getJSONObject(script!!.getString("id")).getString("blobId"))
            JSONObject().put("updated", JSONObject())
        }
        "PushSubscription/get" -> JSONObject().put("list", JSONArray()
            .put(JSONObject().put("id", "old").put("deviceClientId", "phone"))
            .put(JSONObject().put("id", "tablet1").put("deviceClientId", "tablet")))
        "PushSubscription/set" -> JSONObject()
            .put("created", JSONObject().put("p", JSONObject().put("id", "new").put("expires", "2026-10-31T00:00:00Z")))
            .put("updated", JSONObject().put("new", JSONObject.NULL))
        else -> JSONObject()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun rule(id: String) = Rule(id = id, conditions = listOf(Condition(Condition.Field.SENDER, "$id@example.com")), read = true)

    @Test
    fun rulesAreEditedInPlaceAndReordered() = runBlocking<Unit> {
        blobs["b0"] = Sieve.build(listOf(rule("a"), rule("b")))
        script = JSONObject().put("id", "s1").put("name", Sieve.NAME).put("blobId", "b0").put("isActive", true)
        val settings = client.settings
        assertEquals(listOf("a", "b", "c"), settings.create(rule("c")).map { it.id })
        assertEquals(listOf("c", "a", "b"), settings.reorder(listOf("c", "a", "b")).map { it.id })
        assertEquals(listOf("c", "b"), settings.remove(rule("a")).map { it.id })
        val flagged = rule("b").copy(flag = true)
        assertEquals(listOf(rule("c"), flagged), settings.update(flagged))
        assertEquals(listOf(rule("c"), flagged), settings.rules())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.reorder(listOf("c")) } }
    }

    @Test
    fun registeringReplacesThisDevicesSubscription() = runBlocking<Unit> {
        val entry = client.settings.registerPush(Subscription("https://push.example.com/up/1", "phone"))
        assertEquals("new", entry.id)
        val sets = calls.filter { it.first == "PushSubscription/set" }.map { it.second }
        assertEquals(listOf("old"), sets[0].getJSONArray("destroy").let { a -> (0 until a.length()).map(a::getString) })
        assertEquals("phone", sets[1].getJSONObject("create").getJSONObject("p").getString("deviceClientId"))
        assertEquals(1, client.settings.unregisterPush("tablet"))
    }

    @Test
    fun aVerificationPushIsAnswered() = runBlocking<Unit> {
        val push = client.settings.receive("""{"@type":"PushVerification","pushSubscriptionId":"new","verificationCode":"c0de"}""")
        assertEquals(Push.Verification("new", "c0de"), push)
        val set = calls.last { it.first == "PushSubscription/set" }.second
        assertEquals("c0de", set.getJSONObject("update").getJSONObject("new").getString("verificationCode"))
    }

    @Test
    fun aMailChangeRunsASync() = runBlocking<Unit> {
        assertEquals(0L, client.sync.state.value.synced)
        val push = client.settings.receive("""{"@type":"StateChange","changed":{"acc1":{"Email":"e2","Thread":"e2","Mailbox":"m1"}}}""")
        assertTrue(push is Push.Change)
        assertTrue(client.sync.state.value.synced > 0)
    }

    @Test
    fun anFcmDataMessageIsReceivedFromItsPushEntry() = runBlocking<Unit> {
        val body = """{"@type":"PushVerification","pushSubscriptionId":"new","verificationCode":"f1"}"""
        assertEquals(Push.Verification("new", "f1"), client.settings.receive(mapOf("push" to body)))
        assertEquals("f1", calls.last { it.first == "PushSubscription/set" }.second.getJSONObject("update").getJSONObject("new").getString("verificationCode"))
        assertEquals(Push.Rights(4), client.settings.receive(JSONObject().put("push", """{"@type":"Rights","epoch":4}""").toString()))
        assertNull(client.settings.receive(mapOf("other" to "x")))
    }

    @Test
    fun otherPushesAreHandledOrIgnored() = runBlocking<Unit> {
        assertEquals(Push.Rights(3), client.settings.receive("""{"@type":"Rights","epoch":3}"""))
        assertNull(client.settings.receive("""{"hello":"world"}"""))
        assertTrue(calls.isEmpty())
    }
}
