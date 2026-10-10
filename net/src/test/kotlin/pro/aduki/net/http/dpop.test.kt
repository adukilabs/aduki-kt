package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.crypto.dpop.Key
import pro.aduki.crypto.dpop.Software
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class DpopTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }

    private fun dec(s: String) = Base64.getUrlDecoder().decode(s)
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Independent verifier: rebuilds the public key from the proof's own JWK. */
    private fun verify(proof: String): Pair<JSONObject, JSONObject> {
        val (h, b, s) = proof.split('.')
        val header = JSONObject(String(dec(h)))
        val jwk = header.getJSONObject("jwk")
        assertEquals("dpop+jwt", header.getString("typ"))
        assertFalse("no private part in a proof", jwk.has("d"))
        val ok = when (header.getString("alg")) {
            "ES256" -> {
                val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
                val point = ECPoint(BigInteger(1, dec(jwk.getString("x"))), BigInteger(1, dec(jwk.getString("y"))))
                val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
                assertEquals(64, dec(s).size)
                Signature.getInstance("SHA256withECDSAinP1363Format").run {
                    initVerify(pub); update("$h.$b".toByteArray()); verify(dec(s))
                }
            }
            "EdDSA" -> {
                val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
                val pub = KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(prefix + dec(jwk.getString("x"))))
                Signature.getInstance("Ed25519").run {
                    initVerify(pub); update("$h.$b".toByteArray()); verify(dec(s))
                }
            }
            else -> false
        }
        assertTrue("signature verifies", ok)
        return header to JSONObject(String(dec(b)))
    }

    private fun bound(key: Key, jkt: String = key.thumbprint()): String {
        val body = b64("""{"sub":"u1","cnf":{"jkt":"$jkt"}}""".toByteArray())
        return "eyJhbGciOiJFZERTQSJ9.$body.sig"
    }

    @Test
    fun es256ProofHasTheRequiredClaimsAndVerifies() {
        val key = Software.p256()
        val dpop = Dpop(key, clock = { 1_700_000_000_500 })
        val (header, claims) = verify(dpop.proof("post", "https://id.aduki.pro/v1/tokens?x=1#f"))
        assertEquals("ES256", header.getString("alg"))
        assertEquals("POST", claims.getString("htm"))
        assertEquals("https://id.aduki.pro/v1/tokens", claims.getString("htu"))
        assertEquals(1_700_000_000L, claims.getLong("iat"))
        assertTrue(claims.getString("jti").isNotEmpty())
        assertFalse(claims.has("ath"))
        assertFalse(claims.has("nonce"))
    }

    @Test
    fun eddsaProofVerifiesToo() {
        verify(Dpop(Software.ed25519()).proof("GET", "https://mail.aduki.pro/v1/user"))
    }

    @Test
    fun jtiNeverRepeats() {
        val dpop = Dpop(Software.p256())
        val ids = (1..200).map { verify(dpop.proof("GET", "https://a/b")).second.getString("jti") }.toSet()
        assertEquals(200, ids.size)
    }

    @Test
    fun athIsTheSha256OfTheAccessToken() {
        val dpop = Dpop(Software.p256())
        val claims = verify(dpop.proof("GET", "https://a/b", access = "tok", nonce = "n1")).second
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest("tok".toByteArray())), claims.getString("ath"))
        assertEquals("n1", claims.getString("nonce"))
    }

    @Test
    fun thumbprintFollowsRfc7638AndNeverExposesThePrivateKey() {
        val key = Software.p256()
        val jwk = key.jwk
        val canonical = """{"crv":"P-256","kty":"EC","x":"${jwk["x"]}","y":"${jwk["y"]}"}"""
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())), key.thumbprint())
        assertFalse(jwk.containsKey("d"))
        assertFalse(key.toString().contains(jwk.getValue("x")))
        val ed = Software.ed25519()
        assertEquals(
            b64(MessageDigest.getInstance("SHA-256").digest("""{"crv":"Ed25519","kty":"OKP","x":"${ed.jwk["x"]}"}""".toByteArray())),
            ed.thumbprint()
        )
        assertNotEquals(key.thumbprint(), Software.p256().thumbprint())
    }

    @Test
    fun signInStyleRequestGetsAProofWithoutAth() {
        val http = OkHttpClient.Builder().addInterceptor(Dpop(Software.p256())).build()
        server.enqueue(MockResponse().setBody("{}"))
        http.newCall(Request.Builder().url(server.url("/v1/sessions")).post(okhttp3.RequestBody.create(null, "{}")).build()).execute().close()
        val sent = server.takeRequest()
        val claims = verify(sent.getHeader("DPoP")!!).second
        assertEquals(server.url("/v1/sessions").toString(), claims.getString("htu"))
        assertFalse(claims.has("ath"))
        assertNull(sent.getHeader("Authorization"))
    }

    @Test
    fun boundTokenUsesTheDpopSchemeAndOthersAreLeftAlone() {
        val key = Software.p256()
        val http = OkHttpClient.Builder().addInterceptor(Dpop(key)).build()
        val token = bound(key)
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        fun get(auth: String) = http.newCall(Request.Builder().url(server.url("/v1/user")).header("Authorization", auth).build()).execute().close()
        get("Bearer $token")
        get("Bearer ${bound(key, "other-key")}")
        get("Key hm_secret")

        val first = server.takeRequest()
        assertEquals("DPoP $token", first.getHeader("Authorization"))
        val claims = verify(first.getHeader("DPoP")!!).second
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest(token.toByteArray())), claims.getString("ath"))
        val second = server.takeRequest()
        assertTrue(second.getHeader("Authorization")!!.startsWith("Bearer "))
        assertNull(second.getHeader("DPoP"))
        assertNull(server.takeRequest().getHeader("DPoP"))
    }

    @Test
    fun serverNonceIsRetriedOnceWithAFreshJtiAndRemembered() {
        val http = OkHttpClient.Builder().addInterceptor(Dpop(Software.p256())).build()
        server.enqueue(MockResponse().setResponseCode(401).setHeader("DPoP-Nonce", "srv-1"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        fun post() = http.newCall(Request.Builder().url(server.url("/v1/tokens")).post(okhttp3.RequestBody.create(null, "{}")).build()).execute()

        assertEquals(200, post().also { it.close() }.code)
        val first = verify(server.takeRequest().getHeader("DPoP")!!).second
        val retry = verify(server.takeRequest().getHeader("DPoP")!!).second
        assertFalse(first.has("nonce"))
        assertEquals("srv-1", retry.getString("nonce"))
        assertNotEquals("a replayed jti is rejected, so the retry needs a new one", first.getString("jti"), retry.getString("jti"))
        post().close()
        assertEquals("srv-1", verify(server.takeRequest().getHeader("DPoP")!!).second.getString("nonce"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aSecondNonceRefusalIsReturnedNotLooped() {
        val http = OkHttpClient.Builder().addInterceptor(Dpop(Software.p256())).build()
        server.enqueue(MockResponse().setResponseCode(401).setHeader("DPoP-Nonce", "n1"))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("DPoP-Nonce", "n2"))
        val response = http.newCall(Request.Builder().url(server.url("/v1/tokens")).post(okhttp3.RequestBody.create(null, "{}")).build()).execute()
        assertEquals(401, response.code)
        response.close()
        assertEquals(2, server.requestCount)
    }
}
