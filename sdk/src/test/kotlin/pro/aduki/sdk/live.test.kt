package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.net.http.Condition
import pro.aduki.net.http.Subscription
import pro.aduki.net.http.Vacation
import java.time.Instant

/**
 * Tier 5: Live Aduki Server Integration Tests (SERVER REQUIRED - PUT LAST).
 * Executes only when aduki.live=true or ADUKI_LIVE=true is configured.
 */
class LiveTest {

    private fun isLiveEnabled(): Boolean {
        val prop = System.getProperty("aduki.live")
        val env = System.getenv("ADUKI_LIVE")
        return prop == "true" || env == "true"
    }

    @get:Rule
    val dir = TemporaryFolder()

    // An Aduki ID access token (ADUKI_TOKEN) or a tenant API key (ADUKI_KEY).
    private fun live(store: Boolean = false): Aduki {
        val token = System.getProperty("aduki.token") ?: System.getenv("ADUKI_TOKEN")
        val key = System.getProperty("aduki.key") ?: System.getenv("ADUKI_KEY")
        assumeTrue("Skipping live tests: set ADUKI_TOKEN or ADUKI_KEY", !token.isNullOrBlank() || !key.isNullOrBlank())
        val builder = Aduki.builder().endpoint(liveEndpoint())
        if (!token.isNullOrBlank()) builder.token(token) else builder.key(key!!)
        if (store) builder.store(dir.newFolder("db"))
        return builder.build()
    }

    private fun liveEndpoint(): String {
        return System.getProperty("aduki.endpoint")
            ?: System.getenv("ADUKI_ENDPOINT")
            ?: Endpoints.REST
    }

    @Before
    fun requireServer() {
        assumeTrue("Skipping Tier 5 live server tests: aduki.live is not enabled", isLiveEnabled())
    }

    /**
     * T5-LIVE-01: Live Whoami Resolution
     */
    @Test
    fun whoami() = runBlocking {
        val identity = live().me()
        assertNotNull("Expected live server identity resolution", identity)
        assertTrue(identity!!.user.isNotBlank())
    }

    /**
     * T5-LIVE-02: Client builds against the live endpoint
     */
    @Test
    fun connect() = runBlocking {
        assertTrue(live().lifecycle.active())
    }

    /**
     * T5-LIVE-03: Live Mailbox Listing into the local store
     */
    @Test
    fun mailboxes() = runBlocking {
        val client = live(store = true)
        val hexes = client.sync.mailboxes()
        assertTrue("expected the account's mailboxes", hexes.isNotEmpty())
        val boxes = withTimeout(5_000) { client.mail.mailboxes()!!.first { it.size == hexes.size } }
        assertTrue(boxes.any { it.role == "inbox" })
        client.close()
    }

    /**
     * T5-LIVE-04: Live CONDSTORE Delta Sync of every mailbox, then by role
     */
    @Test
    fun sync() = runBlocking {
        val client = live(store = true)
        assertTrue(client.sync.all())
        assertTrue(client.sync.all(listOf("inbox")))
        assertNull(client.sync.state.value.error)
        client.close()
    }

    /**
     * T5-LIVE-05: Live Outbox Send to the account's own address (ADUKI_ADDRESS)
     */
    @Test
    fun send() = runBlocking {
        val me = System.getProperty("aduki.address") ?: System.getenv("ADUKI_ADDRESS")
        assumeTrue("Skipping live send: set ADUKI_ADDRESS to the account's own address", !me.isNullOrBlank())
        val client = live(store = true)
        val sent = client.mail.send(
            to = listOf(me),
            subject = "Automated Live Tier 5 Test",
            body = "Aduki Android SDK Live Verification"
        )
        assertTrue(sent.hex.isNotBlank())
        assertEquals(0, client.sync.pending())
        client.close()
    }

    /**
     * T5-LIVE-06: Rich send over JMAP (HTML, attachment, bcc) to self, then
     * the body, the attachment, a reply, flags and both searches
     */
    @Test
    fun richSend() = runBlocking {
        val me = System.getProperty("aduki.address") ?: System.getenv("ADUKI_ADDRESS")
        assumeTrue("Skipping live send: set ADUKI_ADDRESS to the account's own address", !me.isNullOrBlank())
        val client = live(store = true)
        val tag = "live-${System.currentTimeMillis()}"
        client.mail.send(
            pro.aduki.core.models.Draft(
                to = listOf(me!!),
                bcc = listOf(me),
                subject = "Rich $tag",
                text = "plain $tag",
                html = "<p>html $tag</p>",
                attachments = listOf(pro.aduki.core.models.Upload("note.txt", "text/plain", bytes = "attached $tag".toByteArray()))
            )
        )
        assertEquals(0, client.sync.pending())

        var found: pro.aduki.store.entities.Message? = null
        repeat(20) {
            if (found != null) return@repeat
            client.sync.all(listOf("inbox"))
            found = client.mail.search(tag)!!.let { flow -> kotlinx.coroutines.withTimeoutOrNull(2_000) { flow.first { it.isNotEmpty() } } }?.firstOrNull()
            if (found == null) Thread.sleep(1_500)
        }
        val msg = found ?: error("the message did not arrive")
        val body = client.mail.body(msg.hex)
        assertTrue(body.html.contains("html $tag"))
        assertTrue(body.text.contains("plain $tag"))
        val file = client.mail.save(body.attachments.single(), dir.newFile("note.txt"))
        assertEquals("attached $tag", file.readText())
        assertTrue(client.mail.find(tag).isNotEmpty())

        client.mail.send(pro.aduki.core.models.Draft(to = listOf(me), subject = "Re: Rich $tag", text = "reply $tag", reply = msg.hex))
        client.mail.setFlag(msg.hex, pro.aduki.store.entities.Message.FLAGGED, true)
        client.sync.flush()
        assertEquals(0, client.sync.pending())
        client.close()
    }

    /**
     * T5-LIVE-07: Live event create, reschedule, answer, cancel and delete through
     * the outbox (`/user/events`), then the account's services and bookings.
     */
    @Test
    fun events() = runBlocking {
        val client = live(store = true)
        val start = java.time.Instant.now().plus(java.time.Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val made = client.scheduling.create(
            pro.aduki.core.models.EventRequest(
                title = "SDK live check", start = start, end = start.plusSeconds(1800), timezone = "Africa/Nairobi",
                attendees = listOf("sdk-live@example.com"), rrule = "FREQ=WEEKLY;COUNT=2", reminders = listOf(10)
            )
        )
        assertEquals(0, client.sync.pending())
        val hex = withTimeout(5_000) {
            client.scheduling.appointments!!.first { list -> list.any { it.uid == made.uid && !it.local() } }
        }.first { it.uid == made.uid }.hex
        try {
            client.scheduling.reschedule(hex, start.plusSeconds(3600), start.plusSeconds(5400))
            // Cancelled events are left out of occurrences, so count before cancelling.
            assertEquals(2, client.scheduling.expand(start, start.plus(java.time.Duration.ofDays(15))).count { it.appointment.hex == hex })
            client.scheduling.cancel(hex, "live check")
            assertEquals(0, client.sync.pending())
            client.scheduling.sync()
            val back = client.scheduling.get(hex)!!
            assertEquals(start.plusSeconds(3600).toEpochMilli(), back.start)
            assertEquals("cancelled", back.status)
            assertEquals(listOf(10), back.alarms())
        } finally {
            client.scheduling.remove(hex)
        }
        assertEquals(0, client.sync.pending())
        client.scheduling.sync()
        assertNull(client.scheduling.get(hex))
        client.scheduling.services()
        client.scheduling.bookings()
        client.close()
    }

    /**
     * T5-LIVE-08: Vacation reply round trip; left off afterwards.
     */
    @Test
    fun vacation() = runBlocking<Unit> {
        val settings = live().settings
        val away = Vacation(true, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-02T00:00:00Z"), "SDK test", "Away")
        try {
            settings.save(away)
            assertEquals(away, settings.vacation())
        } finally {
            settings.save(Vacation())
        }
        assertEquals(false, settings.vacation().enabled)
    }

    /**
     * T5-LIVE-07: Senders: the main one is listed first and is the default.
     */
    @Test
    fun senders() = runBlocking<Unit> {
        val senders = live().settings.senders()
        assertTrue(senders.first().main && senders.first().default)
    }

    /**
     * T5-LIVE-08: Filter rules are stored as the active `aduki` Sieve script,
     * read back exactly, then removed.
     */
    @Test
    fun rules() = runBlocking<Unit> {
        val settings = live().settings
        val rule = pro.aduki.net.http.Rule(conditions = listOf(Condition(Condition.Field.SUBJECT, "aduki-sdk \"live\" test")), move = "SDK Test", read = true)
        val before = settings.rules()
        try {
            assertEquals(before + rule, settings.create(rule))
            assertEquals(before + rule, settings.rules())
        } finally {
            settings.save(before)
        }
        assertEquals(before, settings.rules())
    }

    /**
     * T5-LIVE-09: Push registration shape on a non-routable endpoint: accepted
     * (then removed) or refused with a typed error.
     */
    @Test
    fun push() = runBlocking<Unit> {
        val settings = live().settings
        val device = "aduki-sdk-live-test"
        try {
            val entry = settings.registerPush(Subscription("https://push.invalid/aduki-sdk-live-test", device))
            assertTrue(entry.id.isNotBlank())
        } catch (e: AdukiException.Api) {
            assertEquals("invalidProperties", e.kind)
        } finally {
            settings.unregisterPush(device)
        }
        assertEquals(0, settings.unregisterPush(device))
    }

    /**
     * T5-LIVE-13: The senders carry `isDefault`: exactly one is the default.
     */
    @Test
    fun defaultSender() = runBlocking<Unit> {
        val client = live()
        val list = client.jmap.call("Identity/get", org.json.JSONObject(), setOf(pro.aduki.net.http.Jmap.CORE, pro.aduki.net.http.Jmap.MAIL, pro.aduki.net.http.Jmap.SUBMISSION))
            .getJSONArray("list")
        assertTrue((0 until list.length()).all { list.getJSONObject(it).has("isDefault") })
        assertEquals(1, client.settings.senders().count { it.default })
        assertTrue(client.settings.senders().first().default)
    }

    /**
     * T5-LIVE-14: A native FCM registration (a made-up token): the server
     * accepts the create and drops it later when FCM refuses the token.
     */
    @Test
    fun fcm() = runBlocking<Unit> {
        val settings = live().settings
        val device = "aduki-sdk-live-fcm"
        try {
            val entry = settings.registerPush(Subscription(device = device, fcm = "aduki-sdk-live-test-not-a-real-token"))
            assertTrue(entry.id.isNotBlank())
        } finally {
            settings.unregisterPush(device)
        }
        assertEquals(0, settings.unregisterPush(device))
    }

    /**
     * T5-LIVE-15: A group created without members is kept on the server
     * (`GET /user/contacts/groups` counts it 0) and locally across a sync,
     * then deleted.
     */
    @Test
    fun emptyGroup() = runBlocking<Unit> {
        val client = live(store = true)
        val name = "aduki-sdk-live-${System.currentTimeMillis()}"
        try {
            client.contacts.groups.create(name)
            assertEquals(0, client.sync.pending())
            assertEquals(0, client.contactsApi.groups().single { it.name == name }.count)
            client.contacts.sync()
            assertTrue(name in client.contacts.groups.list())
        } finally {
            client.contacts.groups.delete(name)
        }
        assertEquals(0, client.sync.pending())
        assertTrue(client.contactsApi.groups().none { it.name == name })
        client.contacts.sync()
        assertTrue(name !in client.contacts.groups.list())
        client.close()
    }

    /**
     * T5-LIVE-16: Two created contacts merged with the atomic server merge:
     * one is left with both addresses, the other is gone.
     */
    @Test
    fun merge() = runBlocking<Unit> {
        val client = live(store = true)
        val tag = System.currentTimeMillis()
        val a = client.contacts.create(pro.aduki.core.models.Card(name = "SDK Live $tag", emails = listOf("a$tag@example.com")))
        val b = client.contacts.create(pro.aduki.core.models.Card(name = "SDK Live $tag", emails = listOf("b$tag@example.com")))
        assertEquals(0, client.sync.pending())
        val sent = withTimeout(5_000) {
            client.contacts.observe()!!.first { list -> list.count { (it.uid == a.uid || it.uid == b.uid) && !it.hex.startsWith("contact_") } == 2 }
        }
        val keep = sent.first { it.uid == a.uid }.hex
        val other = sent.first { it.uid == b.uid }.hex
        try {
            client.contacts.merge(keep, listOf(other))
            assertEquals(0, client.sync.pending())
            val rows = client.contactsApi.list().items
            assertTrue(rows.none { it.hex == other })
            assertEquals(setOf("a$tag@example.com", "b$tag@example.com"), rows.single { it.hex == keep }.emails.map { it.removePrefix("mailto:") }.toSet())
        } finally {
            client.contacts.delete(keep)
            runCatching { client.contactsApi.delete(other) }
        }
        assertEquals(0, client.sync.pending())
        client.close()
    }

    /**
     * T5-LIVE-12: A session bound to a device key (DPoP) on mail REST and
     * JMAP, with a P-256 (ES256) and an Ed25519 key. Needs a full sign-in:
     * ADUKI_LOGIN_HANDLE, ADUKI_LOGIN_PASSWORD and ADUKI_LOGIN_TOTP (the
     * authenticator's base32 secret of a dedicated test account).
     */
    @Test
    fun dpop() = runBlocking<Unit> {
        val handle = System.getenv("ADUKI_LOGIN_HANDLE")
        val password = System.getenv("ADUKI_LOGIN_PASSWORD")
        val secret = System.getenv("ADUKI_LOGIN_TOTP")
        assumeTrue("Skipping live DPoP: set ADUKI_LOGIN_HANDLE, ADUKI_LOGIN_PASSWORD and ADUKI_LOGIN_TOTP",
            !handle.isNullOrBlank() && !password.isNullOrBlank() && !secret.isNullOrBlank())
        for (key in listOf(pro.aduki.crypto.dpop.Software.p256(), pro.aduki.crypto.dpop.Software.ed25519())) {
            // An authenticator code is single use: start each sign-in in a fresh 30-second window.
            Thread.sleep(30_000 - System.currentTimeMillis() % 30_000 + 1_000)
            val client = Aduki.login(handle!!, password!!, code = totp(secret!!), endpoint = liveEndpoint(), dpop = key)
            assertNotNull("no session for ${key.alg}", client.session.tokens.value)
            assertTrue("mailboxes over REST with ${key.alg}", client.mailApi.mailboxes().items.isNotEmpty())
            assertTrue("JMAP session with ${key.alg}", client.jmap.session(refresh = true).account.isNotBlank())
            client.logout()
        }
    }

    // RFC 6238 code for [secret] (base32) at the current 30-second step.
    private fun totp(secret: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in secret.uppercase().filter { it in alphabet }) {
            value = (value shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((value shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(out.toByteArray(), "HmacSHA1"))
        val step = System.currentTimeMillis() / 1000 / 30
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val number = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        return "%06d".format(number % 1_000_000)
    }

    /**
     * T5-LIVE-17: Mailbox hierarchy and move over live server.
     */
    @Test
    fun mailboxHierarchy() = runBlocking<Unit> {
        val client = live(store = true)
        val name = "live-box-${System.currentTimeMillis()}"
        client.sync.mailboxes()
        val inbox = client.mail.resolve("inbox") ?: error("no inbox found")
        val child = client.mail.createMailbox(name, parent = inbox)
        client.sync.flush()
        assertEquals(0, client.sync.pending())
        try {
            val created = client.mail.getMailbox(child.hex)
            assertNotNull(created)
            assertEquals(inbox, created!!.parent)

            // Move to root
            client.mail.moveMailbox(child.hex, null)
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            val moved = client.mail.getMailbox(child.hex)
            assertNotNull(moved)
            assertEquals("", moved!!.parent)
        } finally {
            client.mail.deleteMailbox(child.hex)
            client.sync.flush()
        }
        client.close()
    }

    /**
     * T5-LIVE-18: Save and open draft.
     */
    @Test
    fun draftSaveAndOpen() = runBlocking<Unit> {
        val client = live(store = true)
        val tag = "draft-${System.currentTimeMillis()}"
        val draftMsg = client.mail.saveDraft(
            to = listOf("draft@example.com"),
            subject = "Draft $tag",
            body = "Body $tag"
        )
        assertTrue(draftMsg.pending())
        val opened = client.mail.openDraft(draftMsg.hex)
        assertNotNull(opened)
        assertEquals(listOf("draft@example.com"), opened!!.to)
        assertEquals("Draft $tag", opened.subject)
        assertEquals("Body $tag", opened.text)
        client.close()
    }
}
