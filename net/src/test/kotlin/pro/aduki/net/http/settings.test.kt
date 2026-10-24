package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Address
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Filters, Responder, Senders and Subscriptions against a fake JMAP server. */
class SettingsTest {

    private lateinit var server: MockWebServer
    private lateinit var jmap: Jmap

    /** Every method call the server got: name and arguments. */
    private val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()

    /** Method name to the arguments of its response. */
    private val replies = HashMap<String, (JSONObject) -> JSONObject>()

    /** Uploaded blobs by id. */
    private val blobs = HashMap<String, String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                val path = request.path.orEmpty()
                return when {
                    path == "/jmap/session" -> MockResponse().setBody(
                        """{"capabilities":{"urn:ietf:params:jmap:core":{}},
                           "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
                           "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
                           "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
                    )
                    path == "/jmap/upload/acc1" -> {
                        val id = "b${blobs.size + 1}"
                        blobs[id] = request.body.readUtf8()
                        MockResponse().setBody("""{"blobId":"$id","type":"application/sieve","size":1}""")
                    }
                    path.startsWith("/jmap/download/acc1/") -> {
                        val id = path.removePrefix("/jmap/download/acc1/").substringBefore('/')
                        blobs[id]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    }
                    path == "/jmap" -> {
                        val sent = JSONObject(request.body.readUtf8()).getJSONArray("methodCalls")
                        val out = JSONArray()
                        for (i in 0 until sent.length()) {
                            val call = sent.getJSONArray(i)
                            val name = call.getString(0)
                            val args = call.getJSONObject(1)
                            calls += name to args
                            val reply = replies[name]?.invoke(args) ?: JSONObject()
                            out.put(JSONArray().put(name).put(reply).put(call.getString(2)))
                        }
                        MockResponse().setBody(JSONObject().put("methodResponses", out).put("sessionState", "s").toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        jmap = Jmap(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun args(name: String): JSONObject = calls.last { it.first == name }.second

    private val rule = Rule(id = "r1", conditions = listOf(Condition(Condition.Field.SUBJECT, "invoice")), move = "Bills")

    @Test
    fun savingRulesCreatesValidatesAndActivatesTheScript() {
        replies["SieveScript/get"] = { JSONObject().put("list", JSONArray()).put("state", "V-1") }
        replies["SieveScript/validate"] = { JSONObject().put("error", JSONObject.NULL) }
        replies["SieveScript/set"] = { JSONObject().put("created", JSONObject().put("r", JSONObject().put("id", "s1"))) }
        Filters(jmap).save(listOf(rule))

        val validate = args("SieveScript/validate")
        assertEquals("b1", validate.getString("blobId"))
        val set = args("SieveScript/set")
        assertEquals(Sieve.NAME, set.getJSONObject("create").getJSONObject("r").getString("name"))
        assertEquals("b1", set.getJSONObject("create").getJSONObject("r").getString("blobId"))
        assertEquals("#r", set.getString("onSuccessActivateScript"))
        assertTrue(blobs.getValue("b1").contains("fileinto \"Bills\";"))
    }

    @Test
    fun rulesReadBackFromTheStoredScript() {
        blobs["b9"] = Sieve.build(listOf(rule))
        replies["SieveScript/get"] = {
            JSONObject().put("state", "V-2").put("list", JSONArray()
                .put(JSONObject().put("id", "x").put("name", "other").put("blobId", "b0").put("isActive", false))
                .put(JSONObject().put("id", "s1").put("name", Sieve.NAME).put("blobId", "b9").put("isActive", true)))
        }
        val set = Filters(jmap).fetch()
        assertEquals(listOf(rule), set.rules)
        assertTrue(set.active)
        assertEquals("V-2", set.state)
    }

    @Test
    fun anEditUpdatesInPlaceWithTheState() {
        blobs["b9"] = Sieve.build(listOf(rule))
        replies["SieveScript/get"] = {
            JSONObject().put("state", "V-3").put("list", JSONArray()
                .put(JSONObject().put("id", "s1").put("name", Sieve.NAME).put("blobId", "b9").put("isActive", true)))
        }
        replies["SieveScript/validate"] = { JSONObject().put("error", JSONObject.NULL) }
        replies["SieveScript/set"] = { JSONObject().put("updated", JSONObject().put("s1", JSONObject.NULL)) }
        val second = rule.copy(id = "r2", move = "Other")
        val saved = Filters(jmap).edit { it + second }
        assertEquals(listOf(rule, second), saved)
        val set = args("SieveScript/set")
        assertEquals("V-3", set.getString("ifInState"))
        assertEquals("s1", set.getString("onSuccessActivateScript"))
        val blob = set.getJSONObject("update").getJSONObject("s1").getString("blobId")
        assertEquals(listOf(rule, second), Sieve.parse(blobs.getValue(blob)))
    }

    @Test
    fun aScriptTheServerRefusesIsThrown() {
        replies["SieveScript/get"] = { JSONObject().put("list", JSONArray()).put("state", "V-1") }
        replies["SieveScript/validate"] = {
            JSONObject().put("error", JSONObject().put("type", "invalidSieve").put("description", "line 3: bad"))
        }
        replies["SieveScript/set"] = {
            JSONObject().put("notCreated", JSONObject().put("r", JSONObject().put("type", "invalidSieve")))
        }
        val e = assertThrows(AdukiException.Api::class.java) { Filters(jmap).save(listOf(rule)) }
        assertEquals("invalidSieve", e.kind)
        assertTrue(e.message!!.contains("line 3"))
    }

    @Test
    fun noRulesDestroysTheScriptAndSwitchesOffOnlyOurs() {
        replies["SieveScript/get"] = {
            JSONObject().put("state", "V-4").put("list", JSONArray()
                .put(JSONObject().put("id", "s1").put("name", Sieve.NAME).put("blobId", "b9").put("isActive", false)))
        }
        replies["SieveScript/set"] = { JSONObject().put("destroyed", JSONArray().put("s1")) }
        Filters(jmap).save(emptyList())
        val set = args("SieveScript/set")
        assertEquals("s1", set.getJSONArray("destroy").getString(0))
        assertFalse(set.has("onSuccessDeactivateScript"))
    }

    @Test
    fun vacationRoundTrips() {
        replies["VacationResponse/get"] = {
            JSONObject().put("list", JSONArray().put(JSONObject()
                .put("id", "singleton").put("isEnabled", true)
                .put("fromDate", "2026-12-20T00:00:00Z").put("toDate", JSONObject.NULL)
                .put("subject", "Away").put("textBody", "Back in January").put("htmlBody", JSONObject.NULL)))
        }
        val away = Responder(jmap).fetch()
        assertEquals(Vacation(true, Instant.parse("2026-12-20T00:00:00Z"), null, "Away", "Back in January", null), away)

        replies["VacationResponse/set"] = { JSONObject().put("updated", JSONObject().put("singleton", JSONObject.NULL)) }
        Responder(jmap).save(away.copy(to = Instant.parse("2027-01-05T09:30:15.250Z")))
        val patch = args("VacationResponse/set").getJSONObject("update").getJSONObject("singleton")
        assertEquals("2027-01-05T09:30:15Z", patch.getString("toDate"))
        assertTrue(patch.isNull("htmlBody"))
        assertEquals("acc1", args("VacationResponse/set").getString("accountId"))
    }

    @Test
    fun aBackwardsVacationIsRefusedLocally() {
        val bad = Vacation(true, Instant.parse("2027-01-05T00:00:00Z"), Instant.parse("2026-01-05T00:00:00Z"), text = "x")
        assertThrows(IllegalArgumentException::class.java) { Responder(jmap).save(bad) }
    }

    @Test
    fun sendersListMainFirstAndEdit() {
        replies["Identity/get"] = {
            JSONObject().put("list", JSONArray()
                .put(JSONObject().put("id", "i2").put("email", "alias@example.com").put("name", "Alias")
                    .put("textSignature", "--\nA").put("htmlSignature", "").put("replyTo", JSONObject.NULL)
                    .put("bcc", JSONArray().put(JSONObject().put("name", JSONObject.NULL).put("email", "log@example.com")))
                    .put("mayDelete", true))
                .put(JSONObject().put("id", "i1").put("email", "me@example.com").put("name", "Me")
                    .put("textSignature", "").put("htmlSignature", "").put("mayDelete", false)))
        }
        val senders = Senders(jmap).fetchAll()
        assertEquals(listOf("i1", "i2"), senders.map { it.id })
        assertTrue(senders[0].main)
        assertEquals(listOf(Address(null, "log@example.com")), senders[1].bcc)

        replies["Identity/set"] = { JSONObject().put("created", JSONObject().put("s", JSONObject().put("id", "i3").put("mayDelete", true))) }
        val made = Senders(jmap).create(Sender(email = "other@example.com", name = "O", signature = "sig"))
        assertEquals("i3", made.id)
        val create = args("Identity/set").getJSONObject("create").getJSONObject("s")
        assertEquals("other@example.com", create.getString("email"))
        assertEquals("sig", create.getString("textSignature"))

        replies["Identity/set"] = { JSONObject().put("updated", JSONObject().put("i3", JSONObject.NULL)) }
        Senders(jmap).update(made.copy(html = "<b>O</b>"))
        val patch = args("Identity/set").getJSONObject("update").getJSONObject("i3")
        assertFalse(patch.has("email"))
        assertEquals("<b>O</b>", patch.getString("htmlSignature"))
    }

    @Test
    fun aForbiddenSenderIsThrownWithItsType() {
        replies["Identity/set"] = {
            JSONObject().put("notCreated", JSONObject().put("s", JSONObject().put("type", "forbiddenFrom").put("description", "you may not send as this address")))
        }
        val e = assertThrows(AdukiException.Api::class.java) { Senders(jmap).create(Sender(email = "ceo@elsewhere.com")) }
        assertEquals("forbiddenFrom", e.kind)
        replies["Identity/set"] = { JSONObject().put("notDestroyed", JSONObject().put("i1", JSONObject().put("type", "forbidden"))) }
        assertEquals("forbidden", assertThrows(AdukiException.Api::class.java) { Senders(jmap).remove("i1") }.kind)
    }

    @Test
    fun aSubscriptionIsCreatedVerifiedAndRemovedWithoutAnAccount() {
        replies["PushSubscription/set"] = {
            JSONObject().put("created", JSONObject().put("p", JSONObject().put("id", "p1").put("expires", "2026-10-31T00:00:00Z")))
        }
        val subscriptions = Subscriptions(jmap)
        val entry = subscriptions.create(Subscription("https://push.example.com/up/abc", "phone-1", Subscription.Keys("BPub", "auth"), listOf("Email")))
        assertEquals("p1", entry.id)
        assertEquals(Instant.parse("2026-10-31T00:00:00Z"), entry.expires)
        val create = args("PushSubscription/set").getJSONObject("create").getJSONObject("p")
        assertFalse(args("PushSubscription/set").has("accountId"))
        assertEquals("phone-1", create.getString("deviceClientId"))
        assertEquals("BPub", create.getJSONObject("keys").getString("p256dh"))
        assertTrue(create.isNull("verificationCode") || !create.has("verificationCode"))

        replies["PushSubscription/set"] = { JSONObject().put("updated", JSONObject().put("p1", JSONObject.NULL)) }
        subscriptions.verify(Push.Verification("p1", "code9"))
        assertEquals("code9", args("PushSubscription/set").getJSONObject("update").getJSONObject("p1").getString("verificationCode"))

        subscriptions.remove(listOf("p1"))
        assertEquals("p1", args("PushSubscription/set").getJSONArray("destroy").getString(0))
    }

    @Test
    fun aWrongCodeIsThrown() {
        replies["PushSubscription/set"] = {
            JSONObject().put("notUpdated", JSONObject().put("p1", JSONObject().put("type", "invalidProperties")))
        }
        assertThrows(AdukiException.Api::class.java) { Subscriptions(jmap).verify(Push.Verification("p1", "bad")) }
    }

    @Test
    fun aPlainHttpEndpointIsRefusedLocally() {
        assertThrows(IllegalArgumentException::class.java) { Subscription("http://push.example.com/x", "d").validate() }
    }

    @Test
    fun pushPayloadsParse() {
        assertEquals(
            Push.Verification("p1", "c0de"),
            Push.parse("""{"@type":"PushVerification","pushSubscriptionId":"p1","verificationCode":"c0de"}""")
        )
        val change = Push.parse("""{"@type":"StateChange","changed":{"acc1":{"Email":"e9","Thread":"e9","Mailbox":"m4"}}}""")
        assertEquals(Push.Change(mapOf("acc1" to mapOf("Email" to "e9", "Thread" to "e9", "Mailbox" to "m4"))), change)
        assertTrue((change as Push.Change).mail())
        assertFalse(Push.Change(mapOf("acc1" to mapOf("CalendarEvent" to "1"))).mail())
        assertEquals(Push.Rights(7), Push.parse("""{"@type":"Rights","epoch":7}"""))
        assertNull(Push.parse("""{"@type":"Other"}"""))
        assertNull(Push.parse("not json"))
    }

    @Test
    fun aChangeNamingMailboxesTargetsThem() {
        val named = Push.parse("""{"@type":"StateChange","changed":{"acc1":{"Email":"e2","Mailbox":"m2"}},"mailboxes":{"acc1":["aa11","bb22"]}}""") as Push.Change
        assertEquals(listOf("aa11", "bb22"), named.targets())
        val plain = Push.parse("""{"@type":"StateChange","changed":{"acc1":{"Email":"e2"}}}""") as Push.Change
        assertNull(plain.targets())
    }

    @Test
    fun anFcmTokenGoesInsteadOfTheUrl() {
        val fcm = Subscription(device = "phone", fcm = "tok123")
        fcm.validate()
        assertEquals("tok123", fcm.json().getString("fcmToken"))
        // Alone, as the server requires: no url and no keys property.
        assertEquals(setOf("fcmToken", "deviceClientId", "types"), fcm.json().keySet())
        assertThrows(IllegalArgumentException::class.java) { Subscription("https://x.example/p", "phone", fcm = "t").validate() }
    }

    @Test
    fun anFcmDataMessageCarriesThePushAsAString() {
        val body = """{"@type":"StateChange","changed":{"acc1":{"Email":"e2","Thread":"e2","Mailbox":"m2"}},"mailboxes":{"acc1":["aa11"]}}"""
        val data = JSONObject().put("push", body).toString()
        val change = Push.parse(data) as Push.Change
        assertEquals(listOf("aa11"), change.targets())
        assertNull(Push.parse("""{"push":5}"""))
        assertNull(Push.parse("""{"other":"x"}"""))
    }

    @Test
    fun theDefaultSenderIsTheMainOneUnlessTheServerSaysOtherwise() {
        replies["Identity/get"] = {
            JSONObject().put("list", JSONArray()
                .put(JSONObject().put("id", "i1").put("email", "me@example.com").put("mayDelete", false))
                .put(JSONObject().put("id", "i2").put("email", "alias@example.com").put("mayDelete", true)))
        }
        assertEquals(listOf(true, false), Senders(jmap).fetchAll().map { it.default })
        replies["Identity/get"] = {
            JSONObject().put("list", JSONArray()
                .put(JSONObject().put("id", "i1").put("email", "me@example.com").put("mayDelete", false).put("isDefault", false))
                .put(JSONObject().put("id", "i2").put("email", "alias@example.com").put("mayDelete", true).put("isDefault", true)))
        }
        assertEquals(listOf("i2", "i1"), Senders(jmap).fetchAll().map { it.id })
        replies["Identity/set"] = { JSONObject().put("updated", JSONObject().put("i2", JSONObject.NULL)) }
        Senders(jmap).prefer("i2")
        assertTrue(args("Identity/set").getJSONObject("update").getJSONObject("i2").getBoolean("isDefault"))
    }
}
