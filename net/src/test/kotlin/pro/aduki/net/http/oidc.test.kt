package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class OidcTest {
    private lateinit var server: MockWebServer
    private lateinit var issuer: String
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val now = 1_800_000_000L

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        issuer = server.url("/").toString().trimEnd('/')
    }
    @After fun teardown() { server.shutdown() }

    private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun dec(s: String) = Base64.getUrlDecoder().decode(s)

    private fun discovery(methods: String = """["S256"]""") = MockResponse().setBody(
        """{"issuer":"$issuer","authorization_endpoint":"$issuer/oauth/authorize","token_endpoint":"$issuer/oauth/token",
        "userinfo_endpoint":"$issuer/oauth/userinfo","jwks_uri":"$issuer/.well-known/jwks.json",
        "revocation_endpoint":"$issuer/oauth/revocation","code_challenge_methods_supported":$methods}"""
    )

    private fun jwks() = MockResponse().setBody(
        """{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"k1","alg":"EdDSA","use":"sig","x":"${enc(pair.public.encoded.takeLast(32).toByteArray())}"}]}"""
    )

    private fun idToken(access: String, nonce: String, mutate: JSONObject.() -> Unit = {}, sign: Boolean = true): String {
        val atHash = enc(MessageDigest.getInstance("SHA-256").digest(access.toByteArray()).copyOf(16))
        val claims = JSONObject().put("iss", issuer).put("sub", "acc1").put("aud", "client1").put("azp", "client1")
            .put("iat", now - 5).put("exp", now + 600).put("nonce", nonce).put("at_hash", atHash).apply(mutate)
        val head = enc("""{"alg":"EdDSA","kid":"k1"}""".toByteArray())
        val body = enc(claims.toString().toByteArray())
        val sig = Signature.getInstance("Ed25519").run {
            initSign(if (sign) pair.private else KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private)
            update("$head.$body".toByteArray()); sign()
        }
        return "$head.$body.${enc(sig)}"
    }

    private fun tokens(id: String, access: String = "adka_1", refresh: String? = "adkr_1", type: String = "Bearer") = MockResponse().setBody(
        JSONObject().put("access_token", access).put("token_type", type).put("expires_in", 600).put("scope", "openid profile")
            .put("id_token", id).apply { if (refresh != null) put("refresh_token", refresh) }.toString()
    )

    private fun rp(secret: String? = "s3cret", post: Boolean = false, dpop: Dpop? = null) =
        Oidc(OkHttpClient(), issuer, "client1", "app://cb", secret, post, dpop, clock = { now * 1000 })

    private fun callback(begin: Oidc.Begin, code: String = "code1", state: String = begin.state) =
        "app://cb?code=$code&state=$state&iss=$issuer"

    @Test
    fun beginBuildsAPkceS256RequestWithFreshSecrets() {
        server.enqueue(discovery())
        val oidc = rp()
        val a = oidc.begin(listOf("openid", "profile", "offline_access"))
        val b = oidc.begin()
        val url = a.url.toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client1", url.queryParameter("client_id"))
        assertEquals("app://cb", url.queryParameter("redirect_uri"))
        assertEquals("openid profile offline_access", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(enc(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray())), url.queryParameter("code_challenge"))
        assertEquals(43, url.queryParameter("code_challenge")!!.length)
        assertTrue(a.verifier.length in 43..128)
        assertEquals(a.state, url.queryParameter("state"))
        assertEquals(a.nonce, url.queryParameter("nonce"))
        assertTrue(a.state != b.state && a.verifier != b.verifier && a.nonce != b.nonce)
        assertEquals("one discovery fetch", 1, server.requestCount)
    }

    @Test
    fun discoveryWithoutS256OrWithAnotherIssuerIsRefused() {
        server.enqueue(discovery("""["plain"]"""))
        try { rp().discover(); fail() } catch (_: AdukiException.Protocol) {}
        val other = Oidc(OkHttpClient(), "https://evil.example", "c", "app://cb")
        server.enqueue(discovery())
        try { other.discover(); fail() } catch (_: Exception) {}
    }

    @Test
    fun codeExchangeSendsTheVerifierAndValidatesTheIdToken() {
        server.enqueue(discovery())
        val oidc = rp()
        val begin = oidc.begin()
        server.takeRequest()
        server.enqueue(tokens(idToken("adka_1", begin.nonce)))
        server.enqueue(jwks())

        val grant = oidc.finish(begin, callback(begin))

        val token = server.takeRequest()
        assertEquals("/oauth/token", token.path)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("client1:s3cret".toByteArray()), token.getHeader("Authorization"))
        val form = token.body.readUtf8()
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=code1"))
        assertTrue(form.contains("code_verifier=${begin.verifier}"))
        assertTrue(form.contains("redirect_uri=app%3A%2F%2Fcb"))
        assertEquals("adka_1", grant.access)
        assertEquals("adkr_1", grant.refresh)
        assertEquals("acc1", grant.claims!!.getString("sub"))
    }

    @Test
    fun publicClientSendsItsIdInTheBodyAndPostMethodSendsTheSecret() {
        for ((oidc, expect) in listOf(rp(secret = null) to "client_id=client1", rp(post = true) to "client_secret=s3cret")) {
            val server2 = server
            server2.enqueue(discovery())
            val begin = oidc.begin()
            server2.takeRequest()
            server2.enqueue(tokens(idToken("adka_1", begin.nonce)))
            server2.enqueue(jwks())
            oidc.finish(begin, callback(begin))
            val token = server2.takeRequest()
            assertNull(token.getHeader("Authorization"))
            assertTrue(token.body.readUtf8().contains(expect))
            server2.takeRequest()
        }
    }

    private fun failing(mutate: JSONObject.() -> Unit = {}, sign: Boolean = true, state: String? = null, callback: String? = null): AdukiException? {
        server.enqueue(discovery())
        val oidc = rp()
        val begin = oidc.begin()
        server.enqueue(tokens(idToken("adka_1", begin.nonce, mutate, sign)))
        server.enqueue(jwks())
        return try {
            oidc.finish(begin, callback ?: callback(begin, state = state ?: begin.state)); null
        } catch (e: AdukiException) { e }
    }

    private fun refusedRedirect(callback: (Oidc.Begin) -> String): AdukiException? {
        server.enqueue(discovery())
        val oidc = rp()
        val begin = oidc.begin()
        return try { oidc.finish(begin, callback(begin)); null } catch (e: AdukiException) {
            assertEquals("refused before the code was spent", 1, server.requestCount)
            e
        }
    }

    @Test
    fun forgedStateIsRefusedBeforeTheCodeIsSpent() {
        assertTrue(refusedRedirect { "app://cb?code=c&state=forged&iss=$issuer" } is AdukiException.Protocol)
    }

    @Test
    fun errorRedirectsMapToExceptions() {
        assertTrue(refusedRedirect { "app://cb?error=access_denied&state=${it.state}" } is AdukiException.Unauthorized)
    }

    @Test
    fun otherErrorRedirectsAreProtocolErrors() {
        assertTrue(refusedRedirect { "app://cb?error=invalid_scope&error_description=no&state=${it.state}" } is AdukiException.Protocol)
    }

    @Test
    fun badIssuerRedirectIsRefusedBeforeTheCodeIsSpent() {
        server.enqueue(discovery())
        val oidc = rp()
        val begin = oidc.begin()
        try { oidc.finish(begin, "app://cb?code=c&state=${begin.state}&iss=https://evil.example"); fail() } catch (_: AdukiException.Protocol) {}
        assertEquals(1, server.requestCount)
    }

    @Test
    fun idTokenChecksRefuseEveryForgery() {
        assertTrue(failing(sign = false) is AdukiException.Protocol)
        assertTrue(failing(mutate = { put("nonce", "other") }) is AdukiException.Protocol)
        assertTrue(failing(mutate = { put("aud", "someone-else") }) is AdukiException.Protocol)
        assertTrue(failing(mutate = { put("iss", "https://evil.example") }) is AdukiException.Protocol)
        assertTrue(failing(mutate = { put("exp", now - 100) }) is AdukiException.Protocol)
        assertTrue(failing(mutate = { put("at_hash", "AAAAAAAAAAAAAAAAAAAAAA") }) is AdukiException.Protocol)
        assertTrue(failing(mutate = { put("azp", "other") }) is AdukiException.Protocol)
        assertNull(failing())
    }

    @Test
    fun refreshRotatesAndASpentTokenIsUnauthorized() {
        server.enqueue(discovery())
        server.enqueue(MockResponse().setBody("""{"access_token":"adka_2","token_type":"Bearer","expires_in":600,"scope":"openid","refresh_token":"adkr_2"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"reused"}"""))
        val oidc = rp()

        val grant = oidc.refresh("adkr_1", "openid")
        assertEquals("adkr_2", grant.refresh)
        assertNull(grant.claims)
        server.takeRequest()
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=adkr_1") && form.contains("scope=openid"))
        try { oidc.refresh("adkr_1"); fail() } catch (e: AdukiException.Unauthorized) { assertTrue(e.message!!.startsWith("invalid_grant")) }
    }

    @Test
    fun refreshResponseWithoutANewRefreshTokenIsAnAuthError() {
        server.enqueue(discovery())
        server.enqueue(MockResponse().setBody("""{"access_token":"adka_2","token_type":"Bearer","expires_in":600}"""))
        try { rp().refresh("adkr_1"); fail() } catch (_: AdukiException.Auth) {}
    }

    @Test
    fun unknownKidRefetchesTheJwksOnceThenFails() {
        server.enqueue(discovery())
        val oidc = rp()
        val begin = oidc.begin()
        val token = idToken("adka_1", begin.nonce).split('.').let { (_, b, s) -> "${enc("""{"alg":"EdDSA","kid":"zz"}""".toByteArray())}.$b.$s" }
        server.enqueue(tokens(token))
        server.enqueue(jwks())
        server.enqueue(jwks())
        try { oidc.finish(begin, callback(begin)); fail() } catch (_: AdukiException.Protocol) {}
        assertEquals(1 + 1 + 2, server.requestCount)
    }

    @Test
    fun userinfoUsesBearerOrDpopAndRevocationPostsTheToken() {
        server.enqueue(discovery())
        server.enqueue(MockResponse().setBody("""{"sub":"acc1"}"""))
        server.enqueue(MockResponse().setBody("""{"sub":"acc1"}"""))
        server.enqueue(MockResponse().setBody(""))
        val oidc = rp(dpop = Dpop(Software.p256()))

        assertEquals("acc1", oidc.userinfo(Oidc.Grant("adka_1", "Bearer", 600, "openid", null, null)).getString("sub"))
        assertEquals("acc1", oidc.userinfo(Oidc.Grant("adka_2", "DPoP", 600, "openid", null, null)).getString("sub"))
        oidc.revoke("adkr_1")

        server.takeRequest()
        val plain = server.takeRequest()
        assertEquals("/oauth/userinfo", plain.path)
        assertEquals("Bearer adka_1", plain.getHeader("Authorization"))
        assertNull(plain.getHeader("DPoP"))
        val bound = server.takeRequest()
        assertEquals("DPoP adka_2", bound.getHeader("Authorization"))
        val claims = JSONObject(String(dec(bound.getHeader("DPoP")!!.split('.')[1])))
        assertEquals(enc(MessageDigest.getInstance("SHA-256").digest("adka_2".toByteArray())), claims.getString("ath"))
        val revoke = server.takeRequest()
        assertEquals("/oauth/revocation", revoke.path)
        assertTrue(revoke.body.readUtf8().contains("token=adkr_1"))
    }

    @Test
    fun dpopBoundCodeExchangeCarriesAProofWithoutAth() {
        server.enqueue(discovery())
        val oidc = rp(dpop = Dpop(Software.p256()))
        val begin = oidc.begin()
        server.takeRequest()
        server.enqueue(tokens(idToken("adka_1", begin.nonce), type = "DPoP"))
        server.enqueue(jwks())
        assertEquals("DPoP", oidc.finish(begin, callback(begin)).type)
        val claims = JSONObject(String(dec(server.takeRequest().getHeader("DPoP")!!.split('.')[1])))
        assertEquals("$issuer/oauth/token", claims.getString("htu"))
        assertTrue(!claims.has("ath"))
    }
}
