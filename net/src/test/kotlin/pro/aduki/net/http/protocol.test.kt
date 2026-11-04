package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import pro.aduki.core.models.AccountProfile
import pro.aduki.crypto.dpop.Software

/**
 * The cross-app sign-in protocol of SDK 0.8.0 against a fake Aduki ID: error
 * kinds, the DPoP nonce, link tickets, the rich center, `/v1/me`, device
 * groups and key-bound handoffs.
 */
class SignInProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private var now = 1_000_000L
    private val http = OkHttpClient()

    @Before
    fun setup() {
        Dpop.forgetNonces()
        server = MockWebServer().also { it.start() }
        base = server.url("/v1").toString()
    }

    @After
    fun teardown() = server.shutdown()

    private fun ok(data: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")

    private fun failure(code: Int, kind: String, nonce: String? = null) =
        MockResponse().setResponseCode(code)
            .setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m"}}""")
            .apply { if (nonce != null) setHeader("DPoP-Nonce", nonce) }

    private fun signedIn(id: Id) {
        server.enqueue(ok("""{"session":"s1","access":"a1","refresh":"r1","expires":600,"group":"g1"}""", 201))
        id.signIn("ada@aduki.me", "pw", code = "123456", audience = "id")
        server.takeRequest()
    }

    // --- error kinds -------------------------------------------------------

    @Test
    fun refreshFailuresAreTypedByKind() {
        for ((kind, ended) in listOf("auth.reused" to true, "auth.revoked" to true, "auth.expired" to true, "auth.invalid" to true, "auth.factor" to false)) {
            server.enqueue(failure(401, kind))
            val e = assertThrows(AdukiException.Unauthorized::class.java) { Login.refresh(http, base, "r1") }
            assertEquals(kind, e.kind)
            assertEquals(kind, ended, e.ended)
        }
        for (kind in listOf("auth.dpop", "auth.nonce")) {
            server.enqueue(failure(401, kind))
            val e = assertThrows(AdukiException.Proof::class.java) { Login.refresh(http, base, "r1") }
            assertEquals(kind, e.kind)
        }
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse("a 401 without a kind (a proxy) is no verdict on the session",
            assertThrows(AdukiException.Unauthorized::class.java) { Login.refresh(http, base, "r1") }.ended)
    }

    @Test
    fun aRefusedProofKeepsTheRefreshToken() {
        val id = Id(http, base) { now }
        signedIn(id)
        now += 600_000
        server.enqueue(failure(401, "auth.dpop"))
        assertThrows(AdukiException.Proof::class.java) { id.token("id") }
        assertEquals("r1", id.refreshToken())
        assertTrue(id.signedIn())
        server.enqueue(ok("""{"access":"a2","refresh":"r2","expires":600}"""))
        assertEquals("a2", id.token("id"))
        assertEquals("r1", JSONObject(server.takeRequest().let { server.takeRequest() }.body.readUtf8()).getString("refresh"))
    }

    @Test
    fun anEndedSessionDropsTheRefreshToken() {
        val id = Id(http, base) { now }
        signedIn(id)
        now += 600_000
        server.enqueue(failure(401, "auth.revoked"))
        assertThrows(AdukiException.Unauthorized::class.java) { id.token("id") }
        assertFalse(id.signedIn())
    }

    @Test
    fun aNonceChallengeIsRetriedOnceAndTheTokensAreKept() {
        val key = Software.p256()
        val signing = OkHttpClient.Builder().addInterceptor(Dpop(key)).build()
        server.enqueue(failure(401, "auth.nonce", nonce = "100.mac"))
        server.enqueue(ok("""{"access":"a2","refresh":"r2","expires":600}"""))
        val tokens = Login.refresh(signing, base, "r1")
        assertEquals("r2", tokens.refresh)
        val first = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("the same refresh token, sent again with the nonce", first.body.readUtf8(), retry.body.readUtf8())
        val claims = JSONObject(String(java.util.Base64.getUrlDecoder().decode(retry.getHeader("DPoP")!!.split('.')[1])))
        assertEquals("100.mac", claims.getString("nonce"))
    }

    // --- sign-in, tickets and handoffs --------------------------------------

    @Test
    fun aSignInWithLinkReturnsTheGroupAndTicket() {
        server.enqueue(ok("""{"session":"s2","access":"a","refresh":"r","expires":600,"group":"g2","ticket":"adkl_t"}""", 201))
        val opened = Login.signIn(http, base, "ada@acme.example", "pw", code = "123456", link = true)
        assertEquals("g2", opened.group)
        assertEquals("adkl_t", opened.ticket)
        assertEquals("s2", opened.tokens.session)
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getBoolean("link"))

        server.enqueue(ok("""{"session":"s3","access":"a","refresh":"r","expires":600,"group":"g3","ticket":null}""", 201))
        val plain = Login.signIn(http, base, "ada@aduki.me", "pw", code = "123456")
        assertNull(plain.ticket)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("link"))
    }

    @Test
    fun linkingWithATicketMapsPolicyRefusals() {
        val id = Id(http, base) { now }
        signedIn(id)
        server.enqueue(ok("""{"center":"c1","token":"t","expires":9,"accounts":[{"hex":"a1","handle":"ada@aduki.me","knd":"p","unlock":"center","name":"Ada","kind":"personal","tenant":null,"photo":null,"links":"allow"}]}""", 201))
        val view = id.link("adkl_t")
        assertEquals("c1", view.center)
        val sent = server.takeRequest()
        assertEquals("/v1/links", sent.path)
        assertEquals("adkl_t", JSONObject(sent.body.readUtf8()).getString("ticket"))

        server.enqueue(failure(403, "access.denied"))
        assertEquals(403, assertThrows(AdukiException.Network::class.java) { id.link("adkl_t") }.code)
        server.enqueue(failure(409, "entity.conflict"))
        assertEquals(409, assertThrows(AdukiException.Network::class.java) { id.link("adkl_t") }.code)
    }

    @Test
    fun aHandoffIsBoundToTheReceiversKeyAndExpiresOnTheLocalClock() {
        val id = Id(http, base) { now }
        signedIn(id)
        server.enqueue(ok("""{"handoff":"adkh_x","expires":42,"expires_in":120}""", 201))
        val handoff = id.handoff("pro.aduki.calendar", Handoff.BROKER_TTL, jkt = "thumb")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("thumb", body.getString("jkt"))
        assertEquals(120, body.getLong("ttl"))
        assertEquals("server time is ignored: the phone clock may be wrong", now / 1000 + 120, handoff.expires)
        assertFalse(handoff.expired(now / 1000 + 60))
    }

    @Test
    fun aRedeemReturnsTheGroupAndAKeyMismatchIsAProof() {
        server.enqueue(ok("""{"session":"s4","access":"a","refresh":"r","expires":600,"group":"g1"}""", 201))
        assertEquals("g1", Login.open(http, base, "adkh_x").group)
        server.enqueue(failure(401, "auth.dpop"))
        assertThrows(AdukiException.Proof::class.java) { Login.open(http, base, "adkh_x") }
        server.enqueue(failure(401, "auth.invalid"))
        assertTrue(assertThrows(AdukiException.Unauthorized::class.java) { Login.open(http, base, "adkh_x") }.ended)
    }

    // --- profile, center, groups --------------------------------------------

    @Test
    fun meReadsTheWholeProfile() {
        val id = Id(http, base) { now }
        signedIn(id)
        server.enqueue(ok("""{"hex":"00a1","handle":"ada@adalabs.dev","name":"Ada Lovelace","kind":"member",
            "tenant":{"hex":"00c3","name":"Ada Labs","role":"owner","owner":true},"photo":"\"5e1f\"","center":"0012",
            "unlock":"self","session":"00ff","group":"00ee"}"""))
        val me = id.me()
        assertEquals("/v1/me", server.takeRequest().path)
        assertEquals("00a1", me.hex)
        assertEquals("Ada Lovelace", me.name)
        assertEquals(AccountProfile.Kind.Member, me.kind)
        assertEquals("Ada Labs", me.tenant!!.name)
        assertEquals("owner", me.tenant!!.role)
        assertTrue(me.tenant!!.owner)
        assertEquals("\"5e1f\"", me.photo)
        assertEquals("00ee", me.group)
        assertEquals("00ff", me.session)
    }

    @Test
    fun aCenterOfOneAccountIsReadWithCenterNull() {
        val id = Id(http, base) { now }
        signedIn(id)
        server.enqueue(ok("""{"center":null,"token":"t","expires":9,"accounts":[{"hex":"00a1","handle":"ada@aduki.me","knd":"p","unlock":"self",
            "name":"Ada","kind":"personal","tenant":null,"photo":null,"links":"allow"}]}"""))
        val view = id.center()
        assertNull(view.center)
        assertEquals(1, view.accounts.size)
        assertTrue(view.accounts[0].personal)
        assertNull(view.accounts[0].tenant)
        assertNull(view.accounts[0].photo)
        assertEquals("Ada", view.accounts[0].name)
    }

    @Test
    fun aCenterListsTenantsWithTheirLinksPolicy() {
        val view = Center.view(JSONObject("""{"center":"c1","token":"t","expires":9,"accounts":[
            {"hex":"a1","handle":"ada@aduki.me","knd":"p","unlock":"center","name":"Ada","kind":"personal","tenant":null,"photo":null,"links":"allow"},
            {"hex":"b2","handle":"ada@acme.example","knd":"m","ten":"t9","unlock":"self","name":"Ada A.","kind":"member",
             "tenant":{"hex":"00t9","name":"Acme","role":null,"owner":false},"photo":"e1","links":"deny"}]}"""))
        assertEquals("c1", view.center)
        val member = view.accounts[1]
        assertEquals(AccountProfile.Kind.Member, member.kind)
        assertEquals("Acme", member.tenant!!.name)
        assertNull(member.tenant!!.role)
        assertEquals("deny", member.links)
        assertEquals("e1", member.photo)
    }

    @Test
    fun groupSignOutEndsTheGroupOrOneAccountOfIt() {
        val id = Id(http, base) { now }
        signedIn(id)
        server.enqueue(ok("""{"ended":3}"""))
        assertEquals(3, id.endGroup("g1"))
        val all = server.takeRequest()
        assertEquals("DELETE", all.method)
        assertEquals("/v1/sessions?group=g1", all.path)

        server.enqueue(ok("""{"ended":1}"""))
        assertEquals(1, id.endGroup("g7", account = "b2"))
        assertEquals("/v1/sessions?group=g7&account=b2", server.takeRequest().path)

        server.enqueue(failure(404, "entity.missing"))
        assertEquals(0, id.endGroup("g9"))
    }
}
