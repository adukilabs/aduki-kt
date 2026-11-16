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

/** The 1.0.0 REST additions: sender logos and verification, contact extras, event meetings and billing. */
class ExtrasTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() = server.shutdown()

    private fun base() = server.url("/v1").toString()
    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")

    @Test
    fun brandsAreAskedByAddressAndDomainAndEveryKeyIsAnswered() {
        server.enqueue(ok("""[
            {"domain":"aduki.pro","address":"news@aduki.pro","url":"/v1/brands/aduki.svg","source":"aduki","verified":"aduki"},
            {"domain":"acme.example","url":"/v1/brands/acme.svg","source":"bimi","verified":"vmc"},
            {"domain":"aduki.me","url":"/v1/brands/x.svg","source":"aduki","verified":"aduki"}
        ]"""))
        val brands = Brands.lookup(http, base(), listOf("News@Aduki.pro", "acme.example", "aduki.me", "nobody.example"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("news@aduki.pro", body.getJSONArray("addresses").getString(0))
        assertEquals(3, body.getJSONArray("domains").length())

        val aduki = brands.getValue("news@aduki.pro")
        assertTrue(aduki.aduki && aduki.badge)
        assertEquals(server.url("/v1/brands/aduki.svg").toString(), aduki.url)
        assertEquals("vmc", brands.getValue("acme.example").verified)
        // The Aduki mark never belongs to a whole domain.
        assertNull(brands.getValue("aduki.me").verified)
        assertNull(brands.getValue("aduki.me").url)
        assertNull(brands.getValue("nobody.example").url)
    }

    @Test
    fun verificationCarriesTheBadgeTheChecksAndTheCertificate() {
        server.enqueue(ok("""{"id":"m1","from":"news@acme.example","domain":"acme.example","verified":"vmc","passed":true,"official":null,
            "authentication":{"internal":false,"tls":true,"dmarc":{"result":"pass","policy":"reject","domain":"acme.example","aligned":true},
              "dkim":[{"result":"pass","domain":"acme.example","selector":"s1","aligned":true}],"spf":{"result":"pass","domain":"mail.acme.example","aligned":true}},
            "logo":{"url":"/v1/brands/acme.svg","source":"bimi"},
            "bimi":{"record":"acme.example","location":"https://acme.example/l.svg","evidence":"https://acme.example/a.pem","problem":null,
              "certificate":{"kind":"vmc","holder":"Acme","domains":["acme.example"],"trademark":{"office":"USPTO","wordMark":"ACME"}}}}"""))
        val v = Brands.verification(http, base(), "m1")
        assertEquals("/v1/user/mail/m1/verification", server.takeRequest().path)
        assertEquals("m1", v.hex)
        assertEquals("vmc", v.verified)
        assertFalse(v.aduki)
        assertEquals("reject", v.authentication!!.dmarc!!.policy)
        assertEquals("s1", v.authentication!!.dkim.single().selector)
        assertEquals(true, v.authentication!!.tls)
        assertEquals("Acme", v.bimi!!.certificate!!.holder)
        assertEquals("ACME", v.bimi!!.certificate!!.trademark!!.wordMark)
        assertEquals(server.url("/v1/brands/acme.svg").toString(), v.logo!!.url)
    }

    @Test
    fun contactTrashAccountsAndRecent() {
        val contacts = Contacts(http, base())
        server.enqueue(ok("""{"items":[{"id":"t1","card":"c1","name":"Ada","emails":["ada@x.example"],"phones":[],"groups":[],"photo":false,"origin":null,"merged":null,"deleted":"2026-11-16T08:00:00Z","purge":"2026-12-16T08:00:00Z"}]}"""))
        server.enqueue(ok("""{"hex":"c1","name":"Ada","emails":["ada@x.example"]}"""))
        server.enqueue(ok("""{"removed":3}"""))
        server.enqueue(ok("""{"accounts":[{"address":"ada@x.example","account":"a1","handle":"ada","name":null,"colleague":false,"apps":["mail","calls"]}]}"""))
        server.enqueue(ok("""{"items":[{"kind":"mail","id":"m1","title":"Hello","when":"2026-11-16T08:00:00Z","way":"received"}],"sources":["mail"]}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals("c1", contacts.trash().single().card)
        assertEquals("/v1/user/contacts/trash", server.takeRequest().path)
        assertEquals("c1", contacts.restore("t1").hex)
        server.takeRequest().let { assertEquals("POST", it.method); assertEquals("/v1/user/contacts/trash/t1/restore", it.path) }
        assertEquals(3, contacts.emptyTrash())
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals(listOf("mail", "calls"), contacts.accounts("c1").single().apps)
        assertEquals("/v1/user/contacts/c1/accounts", server.takeRequest().path)
        assertEquals("received", contacts.recent("c1", 5).single().way)
        assertEquals("/v1/user/contacts/c1/recent?limit=5", server.takeRequest().path)
        contacts.purge("t1")
        assertEquals("/v1/user/contacts/trash/t1", server.takeRequest().path)
    }

    @Test
    fun anEventMeetingIsAddedReadAndRemoved() {
        val scheduling = Scheduling(http, base())
        val meeting = """{"hex":"e1","title":"Review","starts":"2026-11-17T09:00:00Z","ends":"2026-11-17T10:00:00Z","state":"booked","meeting":"abc-defg-hij","link":"https://calls.aduki.pro/call/abc-defg-hij","guest":null,"error":null,"updated":"2026-11-16T08:00:00Z"}"""
        server.enqueue(ok(meeting, 201))
        server.enqueue(ok(meeting))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"kind":"entity.missing","message":"none"}}"""))
        server.enqueue(ok(meeting.replace("booked", "cancelled")))
        assertEquals("https://calls.aduki.pro/call/abc-defg-hij", scheduling.addMeeting("e1").link)
        assertEquals("POST /v1/user/events/e1/meeting", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("booked", scheduling.meeting("e1")!!.state)
        assertNull(scheduling.meeting("e2"))
        server.takeRequest(); server.takeRequest()
        assertEquals("cancelled", scheduling.removeMeeting("e1").state)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun billingReadsWithAnIdTokenAndPaymentsThroughAPortalSession() {
        val asked = mutableListOf<String?>()
        val billing = Billing(http, Bearer { stale -> asked += stale; if (stale == null) "id1" else "id2" }, base())
        server.enqueue(ok("""{"hex":null,"plan":"free","state":"active","applies":"free","limits":{"storage.bytes":1073741824,"audio.pstn":false,"mail.aliases":null},"package":{},"next":null}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":{"kind":"auth.stale","message":"x"}}"""))
        server.enqueue(ok("""[{"purchase":"p1","addon":"ai.audio.pack.500","key":"ai.audio.minutes","unit":500,"quantity":1,"state":"active","recurring":false,"amount":50000,"currency":"KES"}]"""))
        server.enqueue(ok("""{"session":{"token":"portal1","expires":1800}}"""))
        server.enqueue(ok("""{"items":[{"id":"y1","invoice":"i1","provider":"paystack","channel":"mpesa","amount":50000,"currency":"KES","status":"success","initiated":"2026-11-16T08:00:00Z","settled":null}],"next":null}"""))
        server.enqueue(ok("""{"items":[],"next":null}"""))

        val sub = billing.subscription()
        assertEquals("free", sub.plan)
        assertEquals(1073741824L, sub.limits["storage.bytes"])
        assertEquals(0L, sub.limits["audio.pstn"])
        assertNull(sub.limits["mail.aliases"])
        assertEquals("Bearer id1", server.takeRequest().getHeader("Authorization"))

        assertFalse(billing.addons().single().recurring)
        assertEquals(listOf(null, null, "id1"), asked)
        server.takeRequest(); server.takeRequest()

        assertEquals("mpesa", billing.payments(limit = 10).items.single().channel)
        assertEquals("/v1/billing/session", server.takeRequest().path)
        server.takeRequest().let {
            assertEquals("Portal portal1", it.getHeader("Authorization"))
            assertEquals("/v1/portal/payments?limit=10", it.path)
        }
        billing.invoices()
        // The session is reused while it lasts.
        assertEquals("/v1/portal/invoices", server.takeRequest().path)
        try { billing.usage("2026-13"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun aRefusalIsAnApiErrorWithTheServersKind() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"kind":"entity.missing","message":"member account"}}"""))
        try {
            Billing(http, Bearer { "t" }, base()).subscription()
            fail()
        } catch (e: AdukiException.Api) {
            assertEquals(404, e.status)
            assertEquals("entity.missing", e.kind)
        }
    }
}
