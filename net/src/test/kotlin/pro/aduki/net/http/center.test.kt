package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** The Account Center and device-unlock calls (ADK-AUTH-002 section 5, ADK-AUTH-001 section 9) against a fake server. */
class CenterTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val http = OkHttpClient()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        base = server.url("/v1").toString()
    }
    @After fun teardown() { server.shutdown() }

    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")
    private fun failure(code: Int, kind: String) =
        MockResponse().setResponseCode(code).setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m"}}""")

    private fun centerToken(): String {
        val claims = """{"iss":"https://id","aud":"id","sub":"c1","acc":[
            {"hex":"a1","handle":"ada@aduki.me","knd":"p","unlock":"center"},
            {"hex":"b2","handle":"ada@acme.example","knd":"m","ten":"t9","unlock":"self"}],"iat":1,"exp":2,"jti":"j"}"""
        return "${b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray())}.${b64.encodeToString(claims.toByteArray())}.sig"
    }

    private fun switcherJson() = """{"center":"c1","token":"${centerToken()}","expires":1800003600}"""

    @Test
    fun linkSendsTheOtherAccountsCredentialsAndReadsTheSwitcher() {
        server.enqueue(ok(switcherJson(), 201))
        val switcher = Center.link(http, base, "tok", "ada@acme.example", "pw", code = " 123456 ")
        val request = server.takeRequest()
        assertEquals("/v1/links", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("ada@acme.example", body.getString("handle"))
        assertEquals("123456", body.getString("code"))
        assertFalse(body.has("backup"))
        assertEquals("c1", switcher.center)
        assertEquals(2, switcher.accounts.size)
        assertTrue(switcher.accounts[0].personal)
        assertEquals("center", switcher.accounts[0].unlock)
        assertFalse(switcher.accounts[1].personal)
        assertEquals("t9", switcher.accounts[1].tenant)
        assertEquals("self", switcher.accounts[1].unlock)
        assertNull(switcher.accounts[0].tenant)
    }

    @Test
    fun linkErrorsKeepTheirMeaning() {
        server.enqueue(failure(401, "auth.invalid"))
        try { Center.link(http, base, "tok", "x@y", "bad"); fail() } catch (_: AdukiException.Unauthorized) {}
        server.enqueue(failure(403, "links.denied"))
        try { Center.link(http, base, "tok", "x@y", "pw"); fail() } catch (e: AdukiException.Network) { assertEquals(403, e.code) }
        server.enqueue(failure(409, "entity.conflict"))
        try { Center.link(http, base, "tok", "x@y", "pw"); fail() } catch (e: AdukiException.Network) { assertEquals(409, e.code) }
    }

    @Test
    fun fetchReturnsNullWhenNotLinkedAndAnAccountListOtherwise() {
        server.enqueue(failure(404, "entity.missing"))
        assertNull(Center.fetch(http, base, "tok"))
        server.enqueue(ok(switcherJson()))
        assertEquals(listOf("a1", "b2"), Center.fetch(http, base, "tok")!!.accounts.map { it.hex })
        assertEquals("/v1/center", server.takeRequest().path.also { server.takeRequest() })
    }

    @Test
    fun aCenterTokenWithoutAccountsOrWithGarbageGivesAnEmptySwitcher() {
        assertTrue(Center.accounts("not-a-jwt").isEmpty())
        assertTrue(Center.accounts("a.${b64.encodeToString("{}".toByteArray())}.c").isEmpty())
        assertTrue(Center.accounts("a.!!!.c").isEmpty())
    }

    @Test
    fun unlinkAndRevokeTreat404AsAlreadyGone() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(Center.unlink(http, base, "tok", " 123456 "))
        val unlink = server.takeRequest()
        assertEquals("DELETE", unlink.method)
        assertEquals("/v1/links", unlink.path)
        assertEquals("123456", JSONObject(unlink.body.readUtf8()).getString("code"))
        server.enqueue(failure(404, "entity.missing"))
        assertFalse(Center.unlink(http, base, "tok", "123456"))
        server.enqueue(failure(403, "auth.stepup"))
        try { Center.unlink(http, base, "tok", "123456"); fail() } catch (e: AdukiException.Network) { assertEquals(403, e.code) }

        repeat(2) { server.takeRequest() }
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(Center.revoke(http, base, "tok", "cred1"))
        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/v1/credentials/cred1", revoke.path)
        server.enqueue(failure(404, "entity.missing"))
        assertFalse(Center.revoke(http, base, "tok", "cred1"))
    }

    @Test
    fun registerSendsTheRawPublicKeyAndRefusesAWrongSize() {
        val key = ByteArray(32) { it.toByte() }
        server.enqueue(ok("""{"credential":"cred1","trust_days":60}""", 201))
        assertEquals("cred1", Center.register(http, base, "tok", "Pixel", key, "123456", center = true))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("Pixel", body.getString("device"))
        assertArrayEquals(key, Base64.getUrlDecoder().decode(body.getString("public")))
        assertTrue(body.getBoolean("center"))
        try { Center.register(http, base, "tok", "Pixel", ByteArray(31), "123456"); fail() } catch (_: IllegalArgumentException) {}
        server.enqueue(ok("""{"trust_days":60}"""))
        try { Center.register(http, base, "tok", "Pixel", key, "123456"); fail() } catch (_: AdukiException.Protocol) {}
    }

    @Test
    fun unlockSignsTheRawNonceAndReturnsOneSessionPerAccount() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val nonce = ByteArray(32) { (it * 3).toByte() }
        server.enqueue(ok("""{"nonce":"${b64.encodeToString(nonce)}","expires":"2026-10-11T12:02:00Z"}""", 201))
        server.enqueue(ok("""[{"account":"a1","session":"s1","access":"adka_1","refresh":"adkr_1","expires":600},
            {"account":"b2","session":"s2","access":"adka_2","refresh":"adkr_2","expires":600}]"""))

        val opened = Center.unlock(http, base, "cred1", { raw ->
            Signature.getInstance("Ed25519").run { initSign(pair.private); update(raw); sign() }
        }, audience = "space")

        val challenge = server.takeRequest()
        assertEquals("/v1/credentials/cred1/challenges", challenge.path)
        assertNull(challenge.getHeader("Authorization"))
        val unlock = server.takeRequest()
        assertEquals("/v1/credentials/cred1/unlock", unlock.path)
        val body = JSONObject(unlock.body.readUtf8())
        assertEquals("space", body.getString("audience"))
        assertEquals(b64.encodeToString(nonce), body.getString("nonce"))
        // The signature is over the raw nonce bytes and verifies with the public key.
        val verified = Signature.getInstance("Ed25519").run {
            initVerify(pair.public); update(nonce); verify(Base64.getUrlDecoder().decode(body.getString("signature")))
        }
        assertTrue(verified)
        assertEquals(listOf("a1", "b2"), opened.map { it.account })
        assertEquals("adka_2", opened[1].tokens.token)
        assertEquals("s2", opened[1].tokens.session)
        assertEquals("600", opened[0].tokens.expires)
    }

    @Test
    fun unlockMapsRefusals() {
        val nonce = b64.encodeToString(ByteArray(32))
        server.enqueue(ok("""{"nonce":"$nonce"}""", 201))
        server.enqueue(failure(401, "auth.invalid"))
        try { Center.unlock(http, base, "cred1", { ByteArray(64) }); fail() } catch (_: AdukiException.Unauthorized) {}
        // A revoked or lapsed credential: no challenge at all, the caller must sign in fully.
        server.enqueue(failure(404, "entity.missing"))
        try { Center.unlock(http, base, "cred1", { ByteArray(64) }); fail() } catch (e: AdukiException.Network) { assertEquals(404, e.code) }
    }

    @Test
    fun anAccessTokenIsNeverParsedForCenterData() {
        // Access tokens carry no center data (ADK-AUTH-002 section 7.2): the only
        // source of the switcher is the center token from /center or /links.
        val access = "${b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray())}.${b64.encodeToString("""{"sub":"a1","aud":"mail","scope":"x"}""".toByteArray())}.sig"
        assertTrue(Center.accounts(access).isEmpty())
    }
}
