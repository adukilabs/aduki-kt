package pro.aduki.net.http

import okhttp3.OkHttpClient
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Software
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The aduki Calls client against a fake Calls server and a fake Aduki ID, with DPoP-bound `audio` tokens. */
class CallsTest {
    private lateinit var server: MockWebServer
    private val key = Software.p256()
    private val dpop = Dpop(key)
    private val http = OkHttpClient.Builder().addInterceptor(dpop).build()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val audiences = CopyOnWriteArrayList<String>()
    private var issued = 0
    @Volatile private var answers = ArrayDeque<MockResponse>()

    private fun jwt(n: Int): String {
        val claims = """{"sub":"a1","aud":"audio","jti":"$n","cnf":{"jkt":"${key.thumbprint()}"}}"""
        return "${b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray())}.${b64.encodeToString(claims.toByteArray())}.sig"
    }

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/id/v1/sessions") || path.startsWith("/id/v1/tokens")) {
                    issued++
                    audiences += JSONObject(request.body.readUtf8()).optString("audience")
                    val code = if (path.startsWith("/id/v1/sessions")) 201 else 200
                    return MockResponse().setResponseCode(code)
                        .setBody("""{"success":true,"data":{"session":"s1","access":"${jwt(issued)}","refresh":"adkr_$issued","expires":600}}""")
                }
                seen += request
                synchronized(this@CallsTest) { return answers.removeFirst() }
            }
        }
        server.start()
    }
    @After fun teardown() { server.shutdown() }

    private fun calls(): Calls {
        val id = Id(http, server.url("/id/v1").toString())
        id.signIn("ada@acme.example", "pw", code = "123456", audience = "audio")
        return Calls(http, id, dpop, server.url("/calls/v1").toString())
    }

    private fun answer(vararg responses: MockResponse) = synchronized(this) { answers = ArrayDeque(responses.toList()) }
    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")
    private fun failure(code: Int, kind: String) = MockResponse().setResponseCode(code)
        .setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m","details":null}}""")

    private fun claims(proof: String) = JSONObject(String(Base64.getUrlDecoder().decode(proof.split('.')[1])))
    private fun ath(token: String) = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private val amina = "1f3a0c71e2b45d08"
    private val baraka = "6d28791c8786d0de"
    private val call = "04e61991c100cebb"

    @Test
    fun startRingsPeopleWithADpopBoundTokenAndAnIdempotencyKey() {
        val client = calls()
        answer(ok("""{"call":"$call","state":"open","join":{"signal":"wss://x/v1/signal","room":"$call"},"ringing":{"people":1,"devices":2}}""", 201))
        val started = client.start(listOf(baraka))
        assertEquals(call, started.call)
        assertEquals(2, started.ringing!!.devices)
        assertEquals("wss://x/v1/signal", started.signal)

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/calls/v1/calls", request.path)
        UUID.fromString(request.getHeader("Idempotency-Key"))
        assertEquals(baraka, JSONObject(request.body.readUtf8()).getJSONArray("to").getJSONObject(0).getString("account"))
        val token = request.getHeader("Authorization")!!
        assertTrue(token.startsWith("DPoP "))
        val proof = claims(request.getHeader("DPoP")!!)
        assertEquals("POST", proof.getString("htm"))
        assertEquals(server.url("/calls/v1/calls").toString(), proof.getString("htu"))
        assertEquals(ath(token.removePrefix("DPoP ")), proof.getString("ath"))
        assertEquals(listOf("audio"), audiences)
    }

    @Test
    fun aCallWithNobodyToRingSendsAnEmptyBody() {
        val client = calls()
        answer(ok("""{"call":"$call"}""", 201))
        client.start()
        assertEquals("{}", seen.single().body.readUtf8())
    }

    @Test
    fun tokenAndProofAreTheIdentityOfTheCallsSdk() {
        val client = calls()
        val token = client.token()
        assertEquals("audio", claims(token).getString("aud"))
        val proof = client.proof("GET", "wss://calls.aduki.pro/v1/signal?x=1", token)
        val header = JSONObject(String(Base64.getUrlDecoder().decode(proof.split('.')[0])))
        assertEquals("dpop+jwt", header.getString("typ"))
        val body = claims(proof)
        assertEquals("GET", body.getString("htm"))
        assertEquals("wss://calls.aduki.pro/v1/signal", body.getString("htu"))
        assertEquals(ath(token), body.getString("ath"))
        assertFalse(claims(client.proof("GET", "wss://calls.aduki.pro/v1/signal", token)).getString("jti") == body.getString("jti"))
    }

    @Test
    fun ringingListsTheCallsThatRingForMe() {
        val client = calls()
        answer(ok("""{"items":[{"call":"$call","owner":"$amina","invite":{"state":"ringing"}}],"next":null}"""))
        val ringing = client.ringing()
        assertEquals(amina, ringing.single().owner)
        assertEquals("ringing", ringing.single().invite!!.state)
        assertEquals("ringing", seen.single().requestUrl!!.queryParameter("state"))
    }

    @Test
    fun answersAcceptOrDeclineAndAnAnsweredRingIsAConflict() {
        val client = calls()
        answer(
            ok("""{"ticket":"t1","expires":60,"call":"$call"}""", 201),
            ok("""{"ok":true}""", 201),
            failure(409, "state.conflict")
        )
        assertEquals("t1", client.answer(call, accept = true)!!.ticket)
        assertEquals("accept", JSONObject(seen[0].body.readUtf8()).getString("decision"))
        assertEquals("/calls/v1/calls/$call/answers", seen[0].path)
        assertNull(client.answer(call, accept = false))
        assertEquals("decline", JSONObject(seen[1].body.readUtf8()).getString("decision"))
        try {
            client.answer(call, accept = true)
            fail()
        } catch (e: AdukiException.Api) {
            assertEquals(409, e.status)
            assertEquals("state.conflict", e.kind)
        }
    }

    @Test
    fun ticketsLeavingAndRelaysUseTheirRoutes() {
        val client = calls()
        answer(
            ok("""{"ticket":"t2","expires":60,"call":"$call"}""", 201),
            MockResponse().setResponseCode(204),
            ok("""{"servers":[],"ttl":0,"expires":null,"policy":{"iceTransportPolicy":"all"}}""")
        )
        assertEquals("t2", client.ticket(call).ticket)
        client.leave(call)
        assertEquals(0, client.relays().getInt("ttl"))
        assertEquals(listOf("POST", "DELETE", "POST"), seen.map { it.method })
        assertEquals(
            listOf("/calls/v1/calls/$call/tokens", "/calls/v1/calls/$call/participants/me", "/calls/v1/relays"),
            seen.map { it.path }
        )
    }

    @Test
    fun aDeviceRegistersItsFcmTokenOrNone() {
        val client = calls()
        answer(
            ok("""{"id":"68e5ee4b5c280c80","platform":"android","push":{"kind":"fcm"},"state":"active"}""", 201),
            ok("""{"id":"68e5ee4b5c280c81","platform":"android","push":{"kind":"none"},"state":"active"}""", 201),
            ok("""{"id":"68e5ee4b5c280c80","label":"phone"}"""),
            MockResponse().setResponseCode(204)
        )
        assertEquals("68e5ee4b5c280c80", client.register("android", "fcm-token", label = "Pixel").id)
        val first = JSONObject(seen[0].body.readUtf8())
        assertEquals("android", first.getString("platform"))
        assertEquals("fcm", first.getJSONObject("push").getString("kind"))
        assertEquals("fcm-token", first.getJSONObject("push").getString("token"))
        assertEquals("calls", first.getJSONArray("capabilities").getString(0))
        assertEquals("Pixel", first.getString("label"))
        UUID.fromString(seen[0].getHeader("Idempotency-Key"))

        client.register("android", null)
        val second = JSONObject(seen[1].body.readUtf8())
        assertEquals("none", second.getJSONObject("push").getString("kind"))
        assertFalse(second.has("label"))

        client.edit("68e5ee4b5c280c80", label = "phone")
        assertEquals("PATCH", seen[2].method)
        assertEquals("""{"label":"phone"}""", seen[2].body.readUtf8())
        client.remove("68e5ee4b5c280c80")
        assertEquals("DELETE", seen[3].method)
        assertEquals("/calls/v1/devices/68e5ee4b5c280c80", seen[3].path)
    }

    @Test
    fun limitsReportAndQualityUseTheirRoutes() {
        val client = calls()
        answer(
            ok("""{"package":"free","limits":{"audio.calls":1,"audio.minutes":300},"usage":{"audio.calls":0,"audio.minutes":12},"enforced":["audio.calls"]}"""),
            ok("""{"accepted":true,"call":"$call"}""", 201),
            failure(409, "state.conflict"),
            ok("""{"call":"$call","mos":4.2,"people":[{"account":"$amina","mos":4.2,"worstLoss":12}]}"""),
            failure(403, "plan.limit")
        )
        val plan = client.limits()
        assertEquals("free", plan.plan)
        assertEquals(12L, plan.used["audio.minutes"])
        assertEquals(300L, plan.limit("audio.minutes"))
        client.report(call, JSONObject().put("agent", "test").put("loss", 12).put("rtt", 180))
        assertEquals("POST", seen[1].method)
        assertEquals("/calls/v1/calls/$call/reports", seen[1].path)
        assertEquals(12, JSONObject(seen[1].body.readUtf8()).getInt("loss"))
        try {
            client.report(call, JSONObject().put("loss", 1))
            fail()
        } catch (e: AdukiException.Api) {
            assertEquals("state.conflict", e.kind)
        }
        assertEquals(4.2, client.quality(call).getDouble("mos"), 0.0)
        assertEquals("/calls/v1/calls/$call/quality", seen[3].path)
        try {
            client.start(listOf(baraka))
            fail()
        } catch (e: AdukiException.Api) {
            assertEquals(403, e.status)
            assertEquals("plan.limit", e.kind)
        }
        try { client.report(call, JSONObject().put("loss", 1001)); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(5, seen.size)
    }

    @Test
    fun badIdsAndTooManyPeopleAreRefusedBeforeAnyRequest() {
        val client = calls()
        try { client.fetch("NOT-A-HEX"); fail() } catch (_: IllegalArgumentException) {}
        try { client.start(listOf("ABC")); fail() } catch (_: IllegalArgumentException) {}
        try { client.start(List(21) { baraka }); fail() } catch (_: IllegalArgumentException) {}
        try { client.edit(baraka); fail() } catch (_: IllegalArgumentException) {}
        try { client.start(idempotency = "nope"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(seen.isEmpty())
    }

    @Test
    fun a401RenewsTheTokenOnceThenFailsAsUnauthorized() {
        val client = calls()
        answer(failure(401, "auth.stale"), ok("""{"items":[],"next":null}"""))
        assertTrue(client.list().items.isEmpty())
        assertEquals(2, seen.size)
        assertFalse(seen[0].getHeader("Authorization") == seen[1].getHeader("Authorization"))
        assertEquals(listOf("audio", "audio"), audiences)

        seen.clear()
        answer(failure(401, "auth.invalid"), failure(401, "auth.invalid"))
        try { client.capabilities(); fail() } catch (_: AdukiException.Unauthorized) {}
        assertEquals(2, seen.size)
    }

    @Test
    fun aRateLimitCarriesRetryAfterAndANonEnvelopeIsAProtocolError() {
        val client = calls()
        answer(failure(429, "rate.limited").setHeader("Retry-After", "30"), MockResponse().setBody("""{"call":"x"}"""))
        try {
            client.start()
            fail()
        } catch (e: AdukiException.Api) {
            assertEquals("rate.limited", e.kind)
            assertEquals(30L, e.retryAfter)
        }
        try { client.fetch(call); fail() } catch (_: AdukiException.Protocol) {}
        assertNull(seen[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun aMeetingHasACodeAndALinkAndCanBeEndedAndRestarted() {
        val client = calls()
        val meeting = """{"call":"$call","kind":"meeting","state":"open","owner":"$amina","host":"$amina","code":"abc-defg-hij",""" +
            """"title":"Standup","lobby":"guests","created":"2026-11-16T08:00:00Z","expires":"2026-11-17T08:00:00Z","join":{"signal":"wss://x/v1/signal","room":"$call"}}"""
        answer(
            ok(meeting, 201),
            ok(meeting.replace("\"state\":\"open\"", "\"state\":\"expired\",\"closed\":\"2026-11-16T09:00:00Z\"")),
            ok(meeting.replace("\"lobby\"", "\"restarted\":true,\"lobby\"")),
            ok("""{"exists":true,"open":true,"ended":false,"organiser":true,"title":"Standup","call":"$call","code":"abc-defg-hij"}""")
        )
        val made = client.meeting(title = "Standup", lobby = "guests")
        assertTrue(made.meeting)
        assertEquals("abc-defg-hij", made.code)
        assertEquals(server.url("/calls").toString() + "/call/abc-defg-hij", made.link)
        assertEquals(java.time.Instant.parse("2026-11-16T08:00:00Z"), made.created)
        val body = JSONObject(seen[0].body.readUtf8())
        assertEquals("meeting", body.getString("kind"))
        assertEquals("Standup", body.getString("title"))
        assertEquals("guests", body.getString("lobby"))
        UUID.fromString(seen[0].getHeader("Idempotency-Key"))

        val ended = client.end(call)
        assertEquals("DELETE", seen[1].method)
        assertEquals("/calls/v1/calls/$call", seen[1].path)
        assertEquals(java.time.Instant.parse("2026-11-16T09:00:00Z"), ended.closed)

        assertTrue(client.reopen(call).restarted)
        assertEquals("POST", seen[2].method)
        assertEquals("/calls/v1/calls/$call/reopen", seen[2].path)

        val status = client.meetingStatus("https://calls.aduki.pro/call/ABC-DEFG-HIJ?x=1")
        assertEquals("/calls/v1/meetings/abc-defg-hij", seen[3].path)
        assertTrue(status.exists && status.open && status.organiser)
        assertEquals(call, status.call)
    }

    @Test
    fun aLobbyMakesThePersonWaitAndTheLogFilters() {
        val client = calls()
        answer(
            ok("""{"call":"$call","lobby":"waiting","since":"2026-11-16T08:00:00Z"}""", 201),
            ok("""{"call":"$call","lobby":"waiting","since":"2026-11-16T08:00:00Z"}""", 201),
            ok("""{"items":[{"call":"$call","kind":"direct","state":"expired"}],"next":"$amina"}"""),
            ok("""{"count":3,"seen":null}"""),
            ok("""{"count":0,"seen":"2026-11-16T08:00:00Z"}"""),
            MockResponse().setResponseCode(204)
        )
        val entry = client.enter("abcdefghij")
        assertTrue(entry is pro.aduki.core.models.CallEntry.Waiting)
        assertEquals("/calls/v1/calls/abc-defg-hij/tokens", seen[0].path)
        try { client.ticket(call); fail() } catch (e: AdukiException.Api) { assertEquals("lobby.waiting", e.kind) }
        val page = client.list(limit = 10, missed = true, kind = "direct", with = baraka)
        assertEquals(amina, page.next)
        val q = seen[2].requestUrl!!
        assertEquals("missed", q.queryParameter("state"))
        assertEquals("direct", q.queryParameter("kind"))
        assertEquals(baraka, q.queryParameter("with"))
        assertEquals(3, client.missed().count)
        assertEquals(java.time.Instant.parse("2026-11-16T08:00:00Z"), client.seenMissed().seen)
        assertEquals("PUT", seen[4].method)
        assertEquals("/calls/v1/calls/missed/seen", seen[4].path)
        client.hide(call)
        assertEquals("/calls/v1/calls/$call/log", seen[5].path)
    }

    @Test
    fun meetingStatusWithoutAnAccountSendsNoCredentials() {
        answer(ok("""{"exists":false,"open":false,"ended":false}"""))
        val status = Calls.status(OkHttpClient(), "abc defg hij", server.url("/calls/v1").toString())
        assertFalse(status.exists)
        assertNull(status.code)
        assertNull(seen.single().getHeader("Authorization"))
        assertEquals("/calls/v1/meetings/abc-defg-hij", seen.single().path)
    }

    @Test
    fun meetingCodesAreReadFromWhatWasTypedOrPasted() {
        val m = pro.aduki.core.calls.Meetings
        assertEquals("abc-defg-hij", m.code("ABC-DEFG-HIJ"))
        assertEquals("abc-defg-hij", m.code(" abc defg hij "))
        assertEquals("abc-defg-hij", m.code("abc.defg_hij"))
        assertEquals("abc-defg-hij", m.code("https://calls.aduki.pro/call/abc-defg-hij?guest=1#x"))
        assertEquals("abc-defg-hij", m.code("calls.aduki.pro/call/abcdefghij/"))
        assertEquals(call, m.code(call))
        assertNull(m.code("abc-defg-hi"))
        assertNull(m.code("hello"))
        assertNull(m.code(""))
        assertEquals("https://calls.aduki.pro/call/abc-defg-hij", m.link("abcdefghij"))
        try { m.link("nope"); fail() } catch (_: IllegalArgumentException) {}
    }
}
