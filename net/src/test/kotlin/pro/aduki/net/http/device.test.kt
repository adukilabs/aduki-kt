package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Software
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** RFC 8628 client against a local fake of Aduki ID's device endpoints. */
class DeviceTest {
    private lateinit var server: MockWebServer
    private lateinit var issuer: String
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private var now = 1_800_000_000_000L
    private val slept = mutableListOf<Long>()
    private val grantType = "urn:ietf:params:oauth:grant-type:device_code"

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        issuer = server.url("/").toString().trimEnd('/')
    }
    @After fun teardown() { server.shutdown() }

    private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun discovery(device: Boolean = true) = MockResponse().setBody(
        """{"issuer":"$issuer","authorization_endpoint":"$issuer/oauth/authorize","token_endpoint":"$issuer/oauth/token",
        "userinfo_endpoint":"$issuer/oauth/userinfo","jwks_uri":"$issuer/.well-known/jwks.json",
        ${if (device) """"device_authorization_endpoint":"$issuer/oauth/device_authorization",""" else ""}
        "code_challenge_methods_supported":["S256"]}"""
    )

    private fun jwks() = MockResponse().setBody(
        """{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"k1","alg":"EdDSA","use":"sig","x":"${enc(pair.public.encoded.takeLast(32).toByteArray())}"}]}"""
    )

    private fun idToken(access: String): String {
        val atHash = enc(MessageDigest.getInstance("SHA-256").digest(access.toByteArray()).copyOf(16))
        val claims = JSONObject().put("iss", issuer).put("sub", "acc1").put("aud", "tv").put("azp", "tv")
            .put("iat", now / 1000 - 5).put("exp", now / 1000 + 600).put("at_hash", atHash)
        val head = enc("""{"alg":"EdDSA","kid":"k1"}""".toByteArray())
        val body = enc(claims.toString().toByteArray())
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update("$head.$body".toByteArray()); sign() }
        return "$head.$body.${enc(sig)}"
    }

    private fun started(interval: Int? = 5, expires: Int = 600) = MockResponse().setBody(
        JSONObject().put("device_code", "dev-secret").put("user_code", "WDJB-MJHT")
            .put("verification_uri", "https://id.example/device")
            .put("verification_uri_complete", "https://id.example/device?user_code=WDJB-MJHT")
            .put("expires_in", expires).apply { if (interval != null) put("interval", interval) }.toString()
    )

    private fun oauth(error: String, status: Int = 400) =
        MockResponse().setResponseCode(status).setBody("""{"error":"$error","error_description":"d"}""")

    private fun granted() = MockResponse().setBody(
        JSONObject().put("access_token", "adka_1").put("token_type", "Bearer").put("expires_in", 600)
            .put("scope", "openid").put("refresh_token", "adkr_1").put("id_token", idToken("adka_1")).toString()
    )

    private fun tv(secret: String? = null, dpop: Dpop? = null) =
        Oidc(OkHttpClient(), issuer, "tv", "app://cb", secret, dpop = dpop, clock = { now })

    private fun sleeper(advance: Boolean = true): (Long) -> Unit = { ms -> slept += ms; if (advance) now += ms }

    private fun form(body: String) = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }

    private fun pollFailure(rp: Oidc, code: Oidc.DeviceCode): Throwable =
        try { rp.pollDevice(code); throw AssertionError("expected failure") } catch (e: AdukiException) { e }

    @Test
    fun startPostsTheScopeAndClientAndParsesTheCodes() {
        server.enqueue(discovery()); server.enqueue(started())
        val code = tv().startDevice(listOf("openid", "offline_access"))
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/oauth/device_authorization", request.path)
        val body = form(request.body.readUtf8())
        assertEquals("openid offline_access", body["scope"])
        assertEquals("tv", body["client_id"])
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals("https://id.example/device", code.verificationUri)
        assertEquals("https://id.example/device?user_code=WDJB-MJHT", code.verificationUriComplete)
        assertEquals(5L, code.interval)
        assertEquals(now + 600_000, code.expiresAt)
    }

    @Test
    fun confidentialClientUsesBasicAndMissingIntervalDefaultsToFive() {
        server.enqueue(discovery()); server.enqueue(started(interval = null))
        val code = tv(secret = "s3cret").startDevice()
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("Basic " + Base64.getEncoder().encodeToString("tv:s3cret".toByteArray()), request.getHeader("Authorization"))
        assertEquals(5L, code.interval)
    }

    @Test
    fun startRefusals() {
        server.enqueue(discovery(device = false))
        try { tv().startDevice(); fail() } catch (_: AdukiException.Protocol) {}
        try { tv().startDevice(listOf("profile")); fail() } catch (_: IllegalArgumentException) {}

        server.enqueue(oauth("unauthorized_client"))
        val allowed = tv()
        server.enqueue(discovery())
        try { allowed.startDevice(); fail() } catch (_: Exception) {}

        server.enqueue(MockResponse().setBody("""{"device_code":"x"}"""))
        try { allowed.startDevice(); fail() } catch (_: AdukiException.Protocol) {}
    }

    @Test
    fun pollMapsEveryRfc8628Answer() {
        server.enqueue(discovery()); server.enqueue(started())
        val rp = tv()
        val code = rp.startDevice()
        server.takeRequest(); server.takeRequest()

        server.enqueue(oauth("authorization_pending"))
        assertTrue(rp.pollDevice(code) is Oidc.Poll.Pending)
        val sent = form(server.takeRequest().body.readUtf8())
        assertEquals(grantType, sent["grant_type"])
        assertEquals("dev-secret", sent["device_code"])
        assertEquals("tv", sent["client_id"])

        server.enqueue(oauth("slow_down"))
        val slow = rp.pollDevice(code) as Oidc.Poll.SlowDown
        assertEquals(10L, slow.next.interval)
        assertEquals(code.expiresAt, slow.next.expiresAt)
        assertEquals("dev-secret", slow.next.deviceCode)

        server.enqueue(oauth("access_denied"))
        assertTrue(pollFailure(rp, code) is AdukiException.Denied)
        server.enqueue(oauth("expired_token"))
        assertTrue(pollFailure(rp, code) is AdukiException.Expired)
        server.enqueue(oauth("invalid_grant"))
        assertTrue(pollFailure(rp, code) is AdukiException.Unauthorized)
        server.enqueue(oauth("invalid_client", 401))
        assertTrue(pollFailure(rp, code) is AdukiException.Unauthorized)
    }

    @Test
    fun noPollHappensAfterTheLocalDeadline() {
        server.enqueue(discovery()); server.enqueue(started(expires = 30))
        val rp = tv()
        val code = rp.startDevice()
        server.takeRequest(); server.takeRequest()
        now += 30_000
        assertTrue(pollFailure(rp, code) is AdukiException.Expired)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun awaitWaitsTheIntervalGrowsOnSlowDownAndReturnsValidatedTokens() {
        server.enqueue(discovery()); server.enqueue(started(interval = 5))
        val rp = tv()
        val code = rp.startDevice()
        server.enqueue(oauth("authorization_pending"))
        server.enqueue(oauth("slow_down"))
        server.enqueue(oauth("authorization_pending"))
        server.enqueue(granted())
        server.enqueue(jwks())

        val grant = rp.awaitDevice(code, sleeper())

        assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L), slept)
        assertEquals("adka_1", grant.access)
        assertEquals("adkr_1", grant.refresh)
        assertEquals("acc1", grant.claims!!.getString("sub"))
    }

    @Test
    fun awaitStopsWithExpiredWhenTheDeadlinePassesAndAfterAServerExpiry() {
        server.enqueue(discovery()); server.enqueue(started(interval = 5, expires = 12))
        val rp = tv()
        val code = rp.startDevice()
        repeat(2) { server.enqueue(oauth("authorization_pending")) }
        try { rp.awaitDevice(code, sleeper()); fail() } catch (_: AdukiException.Expired) {}
        assertEquals(listOf(5_000L, 5_000L), slept)

        slept.clear()
        server.enqueue(started())
        val again = rp.startDevice()
        server.enqueue(oauth("expired_token"))
        try { rp.awaitDevice(again, sleeper()); fail() } catch (_: AdukiException.Expired) {}
    }

    @Test
    fun awaitThrowsDeniedAndHonoursCancellation() {
        server.enqueue(discovery()); server.enqueue(started())
        val rp = tv()
        val code = rp.startDevice()
        server.enqueue(oauth("access_denied"))
        try { rp.awaitDevice(code, sleeper()); fail() } catch (_: AdukiException.Denied) {}

        val before = server.requestCount
        try { rp.awaitDevice(code, sleeper(), cancelled = { true }); fail() } catch (_: AdukiException.Expired) {}
        assertEquals(before, server.requestCount)
    }

    @Test
    fun awaitRetriesTransientFailuresButNotClientErrors() {
        server.enqueue(discovery()); server.enqueue(started())
        val rp = tv()
        val code = rp.startDevice()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(granted()); server.enqueue(jwks())
        assertEquals("adka_1", rp.awaitDevice(code, sleeper()).access)

        server.enqueue(oauth("invalid_request"))
        try { rp.awaitDevice(code, sleeper()); fail() } catch (e: AdukiException.Network) { assertEquals(400, e.code) }

        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        try { rp.awaitDevice(code, sleeper()); fail() } catch (e: AdukiException.Network) { assertEquals(503, e.code) }
    }

    @Test
    fun badIdTokenIsRefusedAndDpopSignsThePoll() {
        server.enqueue(discovery()); server.enqueue(started())
        val key = Software.p256()
        val rp = tv(dpop = Dpop(key))
        val code = rp.startDevice()
        server.takeRequest(); server.takeRequest()
        server.enqueue(MockResponse().setBody(
            JSONObject().put("access_token", "adka_2").put("token_type", "DPoP").put("expires_in", 600)
                .put("id_token", idToken("another-token")).toString()
        ))
        server.enqueue(jwks())
        try { rp.pollDevice(code); fail() } catch (_: AdukiException.Protocol) {}
        assertNotNull(server.takeRequest().getHeader("DPoP"))
    }

    @Test
    fun nonDeviceCallsKeepTheirOwnErrorMapping() {
        // access_denied on a refresh is a plain failure, never a device outcome.
        server.enqueue(discovery()); server.enqueue(oauth("access_denied"))
        try { tv().refresh("adkr_x"); fail() } catch (e: AdukiException) {
            assertTrue(e is AdukiException.Network)
        }
    }
}
