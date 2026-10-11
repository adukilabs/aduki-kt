package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.time.Instant

class PasskeysTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val http = OkHttpClient()

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        base = server.url("/v1").toString()
    }
    @After fun teardown() { server.shutdown() }

    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")
    private fun failure(code: Int, kind: String) =
        MockResponse().setResponseCode(code).setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m"}}""")

    private val begun = """{"ceremony":"c0ffee","expires":"2026-10-11T12:05:00Z","options":{"challenge":"AAAA","rpId":"aduki.pro","userVerification":"required"}}"""

    private val created = """{"id":"cred_-1","rawId":"cred_-1","type":"public-key","authenticatorAttachment":"platform",
        "clientExtensionResults":{},"response":{"clientDataJSON":"cd","attestationObject":"ao","transports":["internal"]}}"""
    private val asserted = """{"id":"cred_-1","rawId":"x","type":"public-key","response":{"clientDataJSON":"cd",
        "authenticatorData":"ad","signature":"sg","userHandle":"uh"}}"""

    @Test
    fun registrationBeginsWithLabelAndCodeAndFinishesWithTheTrimmedCredential() {
        server.enqueue(ok(begun, 201))
        val challenge = Passkeys.beginRegistration(http, base, "tok", label = " Laptop ", code = " 123456 ")
        val begin = server.takeRequest()
        assertEquals("/v1/passkeys/registrations", begin.path)
        assertEquals("Bearer tok", begin.getHeader("Authorization"))
        val sent = JSONObject(begin.body.readUtf8())
        assertEquals("Laptop", sent.getString("label"))
        assertEquals("123456", sent.getString("code"))
        assertEquals("c0ffee", challenge.ceremony)
        assertEquals(Instant.parse("2026-10-11T12:05:00Z"), challenge.expires)
        assertEquals("AAAA", JSONObject(challenge.optionsJson).getString("challenge"))

        server.enqueue(ok("""{"passkey":"ab12","label":"Laptop","eligible":true,"synced":false,"format":"none"}""", 201))
        val done = Passkeys.finishRegistration(http, base, "tok", challenge, created)
        val finish = server.takeRequest()
        assertEquals("/v1/passkeys/registrations/c0ffee/verifications", finish.path)
        val credential = JSONObject(finish.body.readUtf8()).getJSONObject("credential")
        assertEquals(setOf("id", "type", "response"), credential.keySet())
        assertEquals(setOf("clientDataJSON", "attestationObject"), credential.getJSONObject("response").keySet())
        assertEquals("ab12", done.passkey)
        assertTrue(done.eligible); assertFalse(done.synced)
    }

    @Test
    fun emptyBeginBodyWhenNoLabelOrCode() {
        server.enqueue(ok(begun, 201))
        Passkeys.beginRegistration(http, base, "tok")
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test
    fun stepUpReturnsTheStepTimeAndFailsWith401ForABadAssertion() {
        server.enqueue(ok(begun, 201))
        val challenge = Passkeys.beginStepUp(http, base, "tok")
        assertEquals("/v1/passkeys/stepups", server.takeRequest().path)
        server.enqueue(ok("""{"stepped":1800000000}"""))
        assertEquals(1_800_000_000L, Passkeys.finishStepUp(http, base, "tok", challenge, asserted))
        val finish = server.takeRequest()
        assertEquals("/v1/passkeys/stepups/c0ffee/verifications", finish.path)
        assertEquals("uh", JSONObject(finish.body.readUtf8()).getJSONObject("credential").getJSONObject("response").getString("userHandle"))

        server.enqueue(failure(401, "auth.invalid"))
        try { Passkeys.finishStepUp(http, base, "tok", challenge, asserted); fail() } catch (_: AdukiException.Unauthorized) {}
    }

    @Test
    fun signInNeedsNoCredentialAndReturnsTokens() {
        server.enqueue(ok(begun, 201))
        val challenge = Passkeys.beginSignIn(http, base)
        val begin = server.takeRequest()
        assertEquals("/v1/passkeys/signins", begin.path)
        assertNull(begin.getHeader("Authorization"))

        server.enqueue(ok("""{"session":"s1","access":"adka_1","refresh":"adkr_1","expires":600}""", 201))
        val tokens = Passkeys.finishSignIn(http, base, challenge, asserted, audience = "space", device = "Pixel")
        val finish = server.takeRequest()
        assertEquals("/v1/passkeys/signins/c0ffee/verifications", finish.path)
        assertNull(finish.getHeader("Authorization"))
        val body = JSONObject(finish.body.readUtf8())
        assertEquals("space", body.getString("audience"))
        assertEquals("Pixel", body.getString("device"))
        assertEquals("adka_1", tokens.token)
        assertEquals("adkr_1", tokens.refresh)
        assertEquals("600", tokens.expires)
        assertEquals("s1", tokens.session)

        server.enqueue(ok("""{"session":"s1","refresh":"adkr_1","expires":600}""", 201))
        try { Passkeys.finishSignIn(http, base, challenge, asserted); fail() } catch (_: AdukiException.Auth) {}
    }

    @Test
    fun listAndRemove() {
        server.enqueue(ok("""[{"passkey":"ab12","label":"Laptop","eligible":true,"synced":true,"created":"2026-10-01T00:00:00Z","used":null},
            {"passkey":"cd34","eligible":false,"synced":false,"created":"2026-10-02T00:00:00Z","used":"2026-10-03T00:00:00Z"}]"""))
        val items = Passkeys.list(http, base, "tok")
        assertEquals("/v1/passkeys", server.takeRequest().path)
        assertEquals(2, items.size)
        assertEquals("Laptop", items[0].label); assertTrue(items[0].synced); assertNull(items[0].used)
        assertNull(items[1].label); assertEquals("2026-10-03T00:00:00Z", items[1].used)

        server.enqueue(MockResponse().setResponseCode(204))
        Passkeys.remove(http, base, "tok", "ab12", code = "123456")
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/v1/passkeys/ab12", del.path)
        assertEquals("123456", JSONObject(del.body.readUtf8()).getString("code"))

        server.enqueue(failure(404, "entity.missing"))
        try { Passkeys.remove(http, base, "tok", "zz"); fail() } catch (e: AdukiException.Network) { assertEquals(404, e.code) }
    }

    @Test
    fun stepUpRequiredIsRecognisedAndOtherErrorsKeepTheirStatus() {
        server.enqueue(failure(403, "auth.stepup"))
        val e = try { Passkeys.beginRegistration(http, base, "tok"); null } catch (e: AdukiException) { e }!!
        assertTrue(Passkeys.needsStepUp(e))
        server.enqueue(failure(409, "entity.conflict"))
        val c = try { Passkeys.beginStepUp(http, base, "tok"); null } catch (e: AdukiException.Network) { e }!!
        assertEquals(409, c.code)
        assertFalse(Passkeys.needsStepUp(c))
    }

    @Test
    fun malformedServerAnswersAreProtocolErrors() {
        for (bad in listOf(
            """{"ceremony":"c","expires":"2026-10-11T12:05:00Z","options":{}}""",
            """{"ceremony":"c","expires":"soon","options":{"challenge":"A"}}""",
            """{"expires":"2026-10-11T12:05:00Z","options":{"challenge":"A"}}"""
        )) {
            server.enqueue(ok(bad, 201))
            try { Passkeys.beginSignIn(http, base); fail(bad) } catch (_: AdukiException.Protocol) {}
        }
    }

    @Test
    fun credentialShapingRefusesMalformedInput() {
        fun bad(json: String, registration: Boolean) =
            try { Passkeys.credential(json, registration); false } catch (_: IllegalArgumentException) { true }
        assertTrue(bad("not json", true))
        assertTrue(bad("""{"id":"a b","type":"public-key","response":{"clientDataJSON":"x","attestationObject":"y"}}""", true))
        assertTrue(bad("""{"id":"a","type":"password","response":{"clientDataJSON":"x","attestationObject":"y"}}""", true))
        assertTrue(bad("""{"id":"a","type":"public-key"}""", true))
        assertTrue(bad("""{"id":"a","type":"public-key","response":{"clientDataJSON":"x"}}""", true))
        assertTrue(bad(created, false)) // a registration is not an assertion
        assertTrue(bad(asserted, true)) // and the other way round
        assertFalse(bad(created, true))
        assertFalse(bad(asserted, false))
    }
}
