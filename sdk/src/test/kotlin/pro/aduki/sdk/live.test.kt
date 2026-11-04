package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        if (store) builder.store(dir.newFolder())
        val client = builder.build()
        val refresh = System.getenv("ADUKI_REFRESH")
        val session = System.getenv("ADUKI_SESSION")
        if (!refresh.isNullOrBlank() && !session.isNullOrBlank() && !token.isNullOrBlank()) {
            client.session.update(pro.aduki.core.models.Tokens(token = token, refresh = refresh, session = session, expires = "600"))
        }
        return client
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

    // Sends [draft] to self and returns the hex of the copy that arrives in the inbox.
    private suspend fun arrive(client: Aduki, tag: String, draft: pro.aduki.core.models.Draft): String {
        client.mail.send(draft)
        assertEquals(0, client.sync.pending())
        var found: pro.aduki.store.entities.Message? = null
        repeat(20) {
            if (found != null) return@repeat
            syncAll(client, listOf("inbox"))
            found = client.mail.search(tag)!!.let { flow -> kotlinx.coroutines.withTimeoutOrNull(2_000) { flow.first { it.isNotEmpty() } } }?.firstOrNull()
            if (found == null) Thread.sleep(1_500)
        }
        return (found ?: error("the message did not arrive")).hex
    }

    /**
     * T5-LIVE-06b: Server reading (`urn:aduki:params:jmap:reading`) in each
     * BodyMode, with and without a store; skipped when the server lacks it.
     */
    @Test
    fun bodyModes() = runBlocking {
        val me = System.getProperty("aduki.address") ?: System.getenv("ADUKI_ADDRESS")
        assumeTrue("Skipping live send: set ADUKI_ADDRESS to the account's own address", !me.isNullOrBlank())
        val client = live(store = true)
        assumeTrue(
            "Skipping body modes: the server has no reading support",
            pro.aduki.net.http.Jmap.READING in client.jmap.session().capabilities
        )
        val tag = "modes-${System.currentTimeMillis()}"
        val text = "plain $tag\n\n> quoted $tag\n"
        val hex = arrive(
            client, tag,
            pro.aduki.core.models.Draft(to = listOf(me!!), subject = "Modes $tag", text = text, html = "<p onclick=\"x()\">html $tag</p><script>x()</script>")
        )
        val both = client.mail.body(hex, pro.aduki.core.models.BodyMode.Both, refresh = true)
        assertTrue(both.sanitised && both.hasText && both.hasHtml)
        assertTrue(both.html.contains("html $tag"))
        assertFalse(both.html.contains("<script", ignoreCase = true))
        assertFalse(both.html.contains("onclick", ignoreCase = true))
        assertTrue(both.text.contains("plain $tag"))
        both.quoteStart?.let { assertTrue(both.text.substring(it).startsWith(">")) }

        val rich = client.mail.body(hex, pro.aduki.core.models.BodyMode.Rich)
        assertEquals(both.html, rich.html)
        assertEquals("", rich.text)
        val plain = client.mail.body(hex, pro.aduki.core.models.BodyMode.Plain)
        assertEquals(both.text, plain.text)
        assertEquals("", plain.html)

        // Without a store each mode goes to the server.
        val bare = live()
        for (mode in pro.aduki.core.models.BodyMode.entries) {
            val body = bare.mail.body(hex, mode)
            assertTrue(body.sanitised && body.hasText && body.hasHtml)
            assertEquals(mode != pro.aduki.core.models.BodyMode.Plain, body.html.isNotEmpty())
            assertEquals(mode != pro.aduki.core.models.BodyMode.Rich, body.text.isNotEmpty())
        }

        // An HTML-only message.
        val only = arrive(
            client, "$tag-html",
            pro.aduki.core.models.Draft(to = listOf(me), subject = "Modes $tag-html", html = "<p>only $tag-html</p>")
        )
        val html = client.mail.body(only, pro.aduki.core.models.BodyMode.Rich)
        assertTrue(html.sanitised && html.hasHtml)
        assertTrue(html.html.contains("only $tag-html"))
        bare.close()
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
                attendees = listOfNotNull(System.getenv("ADUKI_ADDRESS")?.ifBlank { null }), rrule = "FREQ=WEEKLY;COUNT=2", reminders = listOf(10)
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
        assertFalse("the draft reached the server and took its id", draftMsg.pending())
        assertEquals(0, client.sync.pending())
        val opened = client.mail.openDraft(draftMsg.hex)
        assertNotNull(opened)
        assertEquals(listOf("draft@example.com"), opened!!.to)
        assertEquals("Draft $tag", opened.subject)
        assertEquals("Body $tag", opened.text)
        client.close()
    }

    /**
     * T5-LIVE-19: List active account sessions and revoke a session over live server.
     */
    @Test
    fun sessionsAndRevoke() = runBlocking<Unit> {
        val handle = System.getenv("ADUKI_LOGIN_HANDLE")
        val password = System.getenv("ADUKI_LOGIN_PASSWORD")
        val secret = System.getenv("ADUKI_LOGIN_TOTP")
        assumeTrue("Skipping live sessions: set ADUKI_LOGIN_HANDLE, ADUKI_LOGIN_PASSWORD and ADUKI_LOGIN_TOTP",
            !handle.isNullOrBlank() && !password.isNullOrBlank() && !secret.isNullOrBlank())
        val tag = "live-${System.currentTimeMillis()}"
        val clients = listOf("old", "new").map { name ->
            // An authenticator code is single use: each sign-in starts in a fresh 30-second window.
            Thread.sleep(30_000 - System.currentTimeMillis() % 30_000 + 1_000)
            Aduki.login(handle!!, password!!, code = totp(secret!!), endpoint = liveEndpoint(), device = "$tag $name")
        }
        val (old, new) = clients
        val sessions = new.sessions()
        val current = sessions.single { it.current }
        assertEquals("the device name is listed", "$tag new", current.device)
        val first = sessions.single { it.device == "$tag old" }
        assertTrue(new.revoke(first.id))
        assertFalse("revoked", new.sessions().any { it.id == first.id })
        assertTrue("the client still renews after the id-audience rotations", new.refresh())
        new.logout()
        old.close()
    }

    private fun signIn(device: String, dpop: pro.aduki.crypto.dpop.Key? = null, store: Boolean = false): Aduki {
        val handle = System.getenv("ADUKI_LOGIN_HANDLE")
        val password = System.getenv("ADUKI_LOGIN_PASSWORD")
        val secret = System.getenv("ADUKI_LOGIN_TOTP")
        assumeTrue("Skipping: set ADUKI_LOGIN_HANDLE, ADUKI_LOGIN_PASSWORD and ADUKI_LOGIN_TOTP",
            !handle.isNullOrBlank() && !password.isNullOrBlank() && !secret.isNullOrBlank())
        // An authenticator code is single use: each sign-in starts in a fresh 30-second window.
        Thread.sleep(30_000 - System.currentTimeMillis() % 30_000 + 1_000)
        return kotlinx.coroutines.runBlocking {
            Aduki.login(handle!!, password!!, code = totp(secret!!), endpoint = liveEndpoint(), dpop = dpop, device = device)
        }
    }

    /**
     * T5-LIVE-20: A handoff opens a peer app's own DPoP-bound session; it
     * works once (aduki-kt#112). A spent refresh token presented again within
     * the replay window returns the same successor (aduki-kt#117).
     */
    @Test
    fun handoffAndReplay() = runBlocking<Unit> {
        val tag = "live-${System.currentTimeMillis()}"
        val signer = signIn("$tag signer")
        val handoff = signer.handoff("pro.aduki.live.peer", ttl = 120)
        assertTrue(handoff.code.startsWith("adkh_"))
        val peer = Aduki.redeem(handoff.code, endpoint = liveEndpoint(), dpop = pro.aduki.crypto.dpop.Software.p256(), device = "$tag peer")
        val peerTokens = peer.session.tokens.value!!
        assertNotEquals("its own session", signer.session.tokens.value!!.session, peerTokens.session)
        assertTrue("the bound peer session reads mail", peer.mailApi.mailboxes().items.isNotEmpty())
        try {
            Aduki.redeem(handoff.code, endpoint = liveEndpoint())
            fail("a handoff works once")
        } catch (_: pro.aduki.core.errors.AdukiException.Unauthorized) {
        }
        assertEquals("$tag peer", signer.sessions().single { it.id == peerTokens.session }.device)

        // replay: rotate, then present the spent token again
        val plain = pro.aduki.net.http.Client.create("")
        val spent = signer.session.tokens.value!!.refresh
        val first = pro.aduki.net.http.Login.refresh(plain, pro.aduki.core.config.Endpoints.ID, spent)
        val again = pro.aduki.net.http.Login.refresh(plain, pro.aduki.core.config.Endpoints.ID, spent)
        assertEquals("the same successor, not a revoked session", first.refresh, again.refresh)
        signer.session.update(signer.session.tokens.value!!.copy(token = again.token, refresh = again.refresh))
        assertTrue("the session lives on", signer.refresh())

        peer.logout()
        signer.logout()
    }

    /**
     * T5-LIVE-21: Scheduling additions on the live server: service colour,
     * revenue in stats, moving an event between calendars (aduki-kt#105, #106, #109).
     */
    @Test
    fun schedulingAdditions() = runBlocking<Unit> {
        val client = live(store = true)
        val tag = System.currentTimeMillis().toString(36)
        // Booking pages need a plan; the test account may not have one yet.
        val service = try {
            client.scheduling.createService(
                pro.aduki.core.models.ServiceRequest(name = "Live $tag", slug = "live-$tag", colour = "#1A73E8")
            )
        } catch (e: pro.aduki.core.errors.AdukiException.Auth) {
            if ("plan.limit" !in e.message.orEmpty()) throw e
            null
        }
        if (service != null) {
            try {
                assertEquals("#1a73e8", service.colour)
                assertNull(client.scheduling.updateService(service.hex, pro.aduki.core.models.ServiceRequest(colour = "")).colour)
                val now = java.time.Instant.now()
                assertTrue("no paid bookings", client.scheduling.stats(service.hex, now.minusSeconds(86_400), now).revenue.isEmpty())
            } finally {
                client.scheduling.removeService(service.hex)
            }
        }

        val other = client.scheduling.createCalendar("Live $tag")
        try {
            val start = java.time.Instant.now().plusSeconds(86_400).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val event = client.scheduling.create(pro.aduki.core.models.EventRequest(title = "Move $tag", start = start, end = start.plusSeconds(1800)))
            client.sync.flush()
            val hex = client.scheduling.expand(start, start.plusSeconds(1800)).first { it.appointment.title == "Move $tag" }.appointment.hex
            client.scheduling.update(hex, pro.aduki.core.models.EventChanges(calendar = other.hex))
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            assertTrue("the server holds it in the other calendar",
                client.schedulingApi.events().any { it.hex == hex && it.calendar == other.hex })
            client.scheduling.remove(hex)
            client.sync.flush()
            assertNotNull(event)
        } finally {
            client.scheduling.removeCalendar(other.hex)
        }
        client.close()
    }
    // ---- Suite checklist (adukilabs/suite#288): what the apps need of the SDK ----

    private fun own(): String {
        val me = System.getProperty("aduki.address") ?: System.getenv("ADUKI_ADDRESS")
        assumeTrue("Skipping: set ADUKI_ADDRESS to the account's own address", !me.isNullOrBlank())
        return me!!
    }

    // Another address of the same account (an alias sender), else its own: mail never leaves the account.
    private suspend fun alias(client: Aduki, me: String): String =
        client.settings.senders().map { it.email }.firstOrNull { !it.equals(me, ignoreCase = true) } ?: me

    // Sync.all, waiting out the server's rate limit (429) a few times.
    private suspend fun syncAll(client: Aduki, mailboxes: List<String>? = null) {
        repeat(6) {
            try {
                client.sync.all(mailboxes)
                return
            } catch (e: AdukiException.Network) {
                if (e.code != 429) throw e
                println("live: rate limited, waiting")
                Thread.sleep(((e.retryAfter ?: 10L).coerceIn(5L, 30L)) * 1_000)
            }
        }
        client.sync.all(mailboxes)
    }

    // Waits until [hex] is synced into the store and returns it.
    private suspend fun stored(client: Aduki, hex: String, check: (pro.aduki.store.entities.Message) -> Boolean = { true }): pro.aduki.store.entities.Message {
        repeat(15) { round ->
            val found = client.mail.listMailboxes().flatMap { box -> client.mail.observe(box.hex)?.value.orEmpty() }
                .firstOrNull { it.hex == hex && check(it) }
            if (found != null) return found
            if (round > 0) Thread.sleep(2_000)
            syncAll(client)
        }
        error("message $hex not synced as expected")
    }

    // Deletes every message the server finds for [tag] (this test's own mail: every copy, sent and received).
    private suspend fun purge(client: Aduki, tag: String) {
        repeat(3) {
            val hits = runCatching { client.mailApi.search(tag, limit = 100).items }.getOrDefault(emptyList())
            if (hits.isEmpty()) return
            hits.forEach { runCatching { client.mailApi.delete(it.hex) } }
        }
    }

    // Flushes the outbox until it is empty, waiting out a rate limit; fails with what is left after a minute.
    private suspend fun drain(client: Aduki, box: io.objectbox.BoxStore? = null) {
        val until = System.currentTimeMillis() + 60_000
        while (true) {
            client.sync.flush()
            if (client.sync.pending() == 0) return
            if (System.currentTimeMillis() > until) {
                val left = box?.boxFor(pro.aduki.store.entities.Outbox::class.java)?.all.orEmpty()
                    .map { it.action + "/" + it.status + "/" + it.attempts + "/" + it.code + "/" + it.reason }
                fail("outbox not drained: ${client.sync.pending()} left $left")
            }
            Thread.sleep(3_000)
        }
    }

    // A second client with its own fresh store: what another device sees on the server.
    private suspend fun observer(): Aduki = live(store = true).also { syncAll(it) }

    /**
     * T5-LIVE-22 (suite#288, Mail): reply, reply all and forward thread with
     * In-Reply-To/References; Cc, an attachment, and another From address.
     */
    @Test
    fun threadingAndRecipients() = runBlocking<Unit> {
        val me = own()
        val client = live(store = true)
        val other = alias(client, me)
        println("live: sending as alias ${other != me}")
        val tag = "thr${System.currentTimeMillis()}"
        val sent = mutableListOf<String>()
        try {
            val original = arrive(
                client, "${tag}o",
                pro.aduki.core.models.Draft(
                    to = listOf(me), cc = listOf(other), subject = "Thread $tag", text = "original ${tag}o",
                    attachments = listOf(pro.aduki.core.models.Upload("a.txt", "text/plain", bytes = "file $tag".toByteArray()))
                )
            ).also { sent += it }
            val first = stored(client, original)
            assertTrue("cc kept", first.ccList().any { it.equals(other, ignoreCase = true) })
            assertTrue(first.hasAttachment)
            assertEquals("file $tag", client.mail.save(client.mail.body(original).attachments.single(), dir.newFile("a-$tag.txt")).readText())
            val messageId = client.mail.headers(original).first { it.first.equals("Message-ID", true) }.second.trim()

            val reply = arrive(client, "${tag}r", pro.aduki.core.models.Draft(to = listOf(me), subject = "Re: Thread $tag", text = "reply ${tag}r\n\n> original ${tag}o", reply = original)).also { sent += it }
            val all = arrive(client, "${tag}a", pro.aduki.core.models.Draft(to = listOf(me), cc = listOf(other), subject = "Re: Thread $tag", text = "all ${tag}a", reply = original)).also { sent += it }
            for (hex in listOf(reply, all)) {
                val headers = client.mail.headers(hex)
                assertEquals(messageId, headers.first { it.first.equals("In-Reply-To", true) }.second.trim())
                assertTrue(headers.first { it.first.equals("References", true) }.second.contains(messageId))
            }
            // Each answer is in a conversation with the original. Mail sent to oneself has several copies of
            // the original under one Message-ID (sent, to, cc), and the server threads an answer with one of them.
            for (hex in listOf(reply, all)) {
                val thread = stored(client, hex).threadId
                assertTrue("the thread holds the original", client.mailApi.thread(thread).items.any { it.subject == "Thread $tag" })
            }
            val forward = arrive(
                client, "${tag}f",
                pro.aduki.core.models.Draft(
                    to = listOf(me), subject = "Fwd: Thread $tag", text = "fwd ${tag}f\n\n---------- Forwarded message ----------\noriginal ${tag}o",
                    forward = original,
                    attachments = listOf(pro.aduki.core.models.Upload("a.txt", "text/plain", bytes = "file $tag".toByteArray()))
                )
            ).also { sent += it }
            assertTrue(client.mail.headers(forward).first { it.first.equals("References", true) }.second.contains(messageId))
            assertTrue(client.mail.body(forward).text.contains("original ${tag}o"))
            assertEquals(1, client.mail.body(forward).attachments.size)
            // The original was marked answered and forwarded.
            stored(client, original) { m -> m.flags and pro.aduki.store.entities.Message.ANSWERED != 0 && m.keywordList().any { it.equals("\$forwarded", true) } }

            // Another From address, over JMAP (html) and over the plain REST send.
            val rich = arrive(client, "${tag}h", pro.aduki.core.models.Draft(to = listOf(me), subject = "From $tag", text = "from ${tag}h", html = "<p>from ${tag}h</p>", from = other)).also { sent += it }
            assertTrue(stored(client, rich).fromEmail.equals(other, ignoreCase = true))
            val plain = arrive(client, "${tag}p", pro.aduki.core.models.Draft(to = listOf(me), subject = "From $tag", text = "from ${tag}p", from = other)).also { sent += it }
            assertTrue(stored(client, plain).fromEmail.equals(other, ignoreCase = true))
        } finally {
            client.mail.remove(sent)
            client.sync.flush()
            purge(client, tag)
            client.close()
        }
    }

    /**
     * T5-LIVE-23 (suite#288, Mail): star, read, move, archive, trash, mark all
     * read, mailbox create/rename/delete, snooze and unsnooze, each seen by
     * another store; then a server-only search hit starred.
     */
    @Test
    fun messageActions() = runBlocking<Unit> {
        val me = own()
        val local = pro.aduki.store.box.Factory.build(dir.newFolder())
        val client = Aduki.builder().endpoint(liveEndpoint()).token(System.getenv("ADUKI_TOKEN")).store(local).build()
        val tag = "act${System.currentTimeMillis()}"
        val a = arrive(client, "${tag}a", pro.aduki.core.models.Draft(to = listOf(me), subject = "Act ${tag}a", text = "a"))
        val b = arrive(client, "${tag}b", pro.aduki.core.models.Draft(to = listOf(me), subject = "Act ${tag}b", text = "b"))
        val box = client.mail.createMailbox("live-$tag")
        client.sync.flush()
        val boxHex = client.mail.listMailboxes().first { it.name.endsWith("live-$tag") }.hex
        try {
            client.mail.setFlag(a, pro.aduki.store.entities.Message.FLAGGED, true)
            client.mail.setFlag(a, pro.aduki.store.entities.Message.SEEN, true)
            drain(client, local)
            val other = observer()
            stored(other, a) { it.flagged() && it.seen() }

            val archive = client.mail.resolve("archive") ?: error("no archive")
            client.mail.move(a, archive)
            client.sync.flush()
            stored(other, a) { it.mailbox == archive }
            val trash = client.mail.resolve("trash") ?: error("no trash")
            client.mail.move(listOf(a), trash)
            client.sync.flush()
            stored(other, a) { it.mailbox == trash }

            client.mail.move(listOf(a, b), boxHex)
            client.mail.setFlags(listOf(a, b), pro.aduki.store.entities.Message.SEEN, false)
            client.sync.flush()
            client.mail.markAllRead(boxHex)
            drain(client, local)
            stored(other, a) { it.mailbox == boxHex && it.seen() }
            stored(other, b) { it.mailbox == boxHex && it.seen() }

            client.mail.renameMailbox(boxHex, "live-$tag-renamed")
            drain(client, local)
            syncAll(other)
            assertTrue(other.mail.listMailboxes().any { it.hex == boxHex && it.name.endsWith("live-$tag-renamed") })

            client.mail.snooze(b, java.time.Instant.now().plusSeconds(3600))
            drain(client, local)
            stored(other, b) { m -> m.snoozed() > 0 && other.mail.getMailbox(m.mailbox)?.name?.endsWith("Snoozed") == true }
            client.mail.unsnooze(b)
            drain(client, local)
            val inbox = client.mail.resolve("inbox")!!
            stored(other, b) { it.mailbox == inbox && !it.seen() && it.snoozed() == 0L }
            other.close()

            // A hit only the server has: found, stored and starred from a store that never synced it.
            val fresh = live(store = true)
            fresh.sync.mailboxes()
            val hit = fresh.mail.find("${tag}b", store = true).first { it.hex == b }
            fresh.mail.setFlag(hit.hex, pro.aduki.store.entities.Message.FLAGGED, true)
            fresh.sync.flush()
            assertEquals(0, fresh.sync.pending())
            fresh.close()
            stored(client, b) { it.flagged() }
            assertNotNull(box)
        } finally {
            client.mail.remove(listOf(a, b))
            client.sync.flush()
            client.mail.deleteMailbox(boxHex)
            client.sync.flush()
            purge(client, tag)
            client.close()
            local.close()
        }
    }

    /**
     * T5-LIVE-24 (suite#288, Mail; aduki-kt#115 on 0.8.x): a draft saved,
     * replaced, opened again from another store, moved and deleted.
     */
    @Test
    fun draftKeptAndRestored() = runBlocking<Unit> {
        val me = own()
        val client = live(store = true)
        val tag = "drf${System.currentTimeMillis()}"
        val first = client.mail.saveDraft(pro.aduki.core.models.Draft(to = listOf(me), subject = "Draft $tag", text = "one"))
        assertFalse(first.pending())
        val second = client.mail.saveDraft(pro.aduki.core.models.Draft(to = listOf(me), cc = listOf(me), subject = "Draft $tag", text = "two"), replace = first.hex)
        assertFalse(second.pending())
        assertEquals(0, client.sync.pending())
        val other = observer()
        syncAll(client)
        try {
            val opened = other.mail.openDraft(second.hex)
            assertEquals("two", opened.text)
            assertEquals(listOf(me), opened.cc)
            assertTrue("the replaced draft is gone", other.mail.find("Draft $tag").none { it.hex == first.hex })
            val drafts = client.mail.resolve("drafts") ?: error("no drafts")
            val archive = client.mail.resolve("archive") ?: error("no archive")
            client.mail.move(second.hex, archive)
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            stored(other, second.hex) { it.mailbox == archive }
            client.mail.move(second.hex, drafts)
            client.sync.flush()
            assertEquals(0, client.sync.pending())
        } finally {
            client.mail.remove(second.hex)
            client.sync.flush()
            purge(client, tag)
            other.close()
            client.close()
        }
    }

    /**
     * T5-LIVE-25 (suite#288, sign-in): a send while offline stays queued in
     * the store and goes out from the next client once the network is back.
     */
    @Test
    fun offlineSendQueues() = runBlocking<Unit> {
        val me = own()
        val token = System.getenv("ADUKI_TOKEN")
        assumeTrue(!token.isNullOrBlank())
        val box = pro.aduki.store.box.Factory.build(dir.newFolder("shared"))
        val tag = "off${System.currentTimeMillis()}"
        val down = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("airplane mode") }.build()
        val offline = Aduki.builder().endpoint(liveEndpoint()).token(token!!).http(down).store(box).build()
        val queued = try {
            offline.mail.send(pro.aduki.core.models.Draft(to = listOf(me), subject = "Offline $tag", text = "offline $tag"))
        } catch (e: Exception) {
            fail("a send while offline must queue, not throw: $e"); error("")
        }
        assertTrue(queued.pending())
        assertEquals(1, offline.sync.pending())
        offline.close()

        val online = Aduki.builder().endpoint(liveEndpoint()).token(token).store(box).build()
        assertEquals("still queued after a restart", 1, online.sync.pending())
        online.sync.flush()
        assertEquals(0, online.sync.pending())
        online.sync.mailboxes()
        var hex: String? = null
        repeat(20) {
            if (hex != null) return@repeat
            hex = online.mail.find(tag).firstOrNull { it.subject == "Offline $tag" && online.mail.getMailbox(it.mailbox)?.role == "inbox" }?.hex
            if (hex == null) Thread.sleep(1_500)
        }
        assertNotNull("the queued message arrived", hex)
        purge(online, tag)
        online.close()
        box.close()
    }

    /**
     * T5-LIVE-26 (suite#288, Settings): rules, auto-reply, and a sender's
     * signature saved and read back by another client, then restored.
     */
    @Test
    fun signatureSync() = runBlocking<Unit> {
        val client = live()
        val sender = client.settings.senders().first()
        val tag = "sig${System.currentTimeMillis()}"
        try {
            client.settings.update(sender.copy(signature = "-- \nLive $tag", html = "<p>Live $tag</p>"))
            val back = live().settings.senders().first { it.id == sender.id }
            assertEquals("-- \nLive $tag", back.signature)
            assertEquals("<p>Live $tag</p>", back.html)
        } finally {
            client.settings.update(sender)
        }
        val restored = live().settings.senders().first { it.id == sender.id }
        assertEquals(sender.signature, restored.signature)
        assertEquals(sender.html, restored.html)
    }

    /**
     * T5-LIVE-27 (suite#288, Contacts): several phones and e-mails, edit,
     * groups (assign, rename, delete), vCard 3.0/4.0 export and import,
     * duplicates, and deleting several, each checked on the server.
     */
    @Test
    fun contactBook() = runBlocking<Unit> {
        val client = live(store = true)
        client.contacts.sync()
        val tag = "ct${System.currentTimeMillis()}"
        val group = "Live $tag"
        val made = mutableSetOf<String>()
        fun server() = client.contactsApi.list().items
        try {
            val ada = client.contacts.create(pro.aduki.core.models.Card(
                name = "Ada $tag", emails = listOf("ada1-$tag@example.com", "ada2-$tag@example.com"),
                phones = listOf("+254700000001", "+254700000002"), company = "Acme $tag"
            ))
            val bob = client.contacts.create(pro.aduki.core.models.Card(name = "Bob $tag", emails = listOf("bob-$tag@example.com")))
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            val rows = withTimeout(10_000) {
                client.contacts.observe()!!.first { list -> list.count { (it.uid == ada.uid || it.uid == bob.uid) && !it.hex.startsWith("contact_") } == 2 }
            }
            val adaHex = rows.first { it.uid == ada.uid }.hex.also { made += it }
            val bobHex = rows.first { it.uid == bob.uid }.hex.also { made += it }
            val row = server().single { it.hex == adaHex }
            assertEquals(2, row.emails.size)
            assertEquals(2, row.phones.size)

            client.contacts.update(adaHex, client.contacts.card(adaHex)!!.copy(name = "Ada Lovelace $tag", notes = "edited"))
            client.contacts.groups.create(group, listOf(adaHex, bobHex))
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            assertEquals("Ada Lovelace $tag", server().single { it.hex == adaHex }.name)
            assertEquals(2, client.contactsApi.groups().single { it.name == group }.count)
            client.contacts.groups.rename(group, "$group renamed")
            client.contacts.groups.remove("$group renamed", listOf(bobHex))
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            assertEquals(1, client.contactsApi.groups().single { it.name == "$group renamed" }.count)
            assertTrue(client.contactsApi.groups().none { it.name == group })
            client.contacts.groups.delete("$group renamed")
            client.sync.flush()
            assertTrue(client.contactsApi.groups().none { it.name.startsWith(group) })
            assertTrue("deleting a group keeps its contacts", server().any { it.hex == adaHex })

            // Export both versions, delete, then import each back.
            val v4 = client.contacts.export(listOf(adaHex, bobHex))
            val v3 = client.contacts.export(listOf(adaHex), pro.aduki.core.vcard.Vcard.V3)
            assertTrue(v4.contains("VERSION:4.0") && v3.contains("VERSION:3.0"))
            assertTrue(v3.contains("ada2-$tag@example.com"))
            client.contacts.delete(adaHex)
            client.contacts.delete(bobHex)
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            assertTrue(server().none { it.hex == adaHex || it.hex == bobHex })
            made.clear()

            val imported = client.contacts.import(v3.replace(ada.uid, "v3-${ada.uid}").byteInputStream())
            assertEquals(1, imported.created)
            val again = client.contacts.import(v4.byteInputStream())
            assertTrue(again.created + again.updated >= 1)
            client.sync.flush()
            assertEquals(0, client.sync.pending())
            client.contacts.sync()
            val back = server().filter { (it.name ?: "").contains(tag) }
            made += back.map { it.hex }
            assertTrue(back.any { it.phones.size == 2 && it.emails.size == 2 })
            val dup = client.contacts.duplicates().firstOrNull { d -> d.contacts.any { (it.name).contains(tag) } }
            assertNotNull("the two Adas share an address", dup)
        } finally {
            client.contacts.sync()
            made += server().filter { (it.name ?: "").contains(tag) }.map { it.hex }
            made.forEach { runCatching { client.contactsApi.delete(it) } }
            runCatching { client.contactsApi.groups().filter { it.name.startsWith(group) }.forEach { client.contacts.groups.delete(it.name) } }
            client.sync.flush()
            client.close()
        }
        assertTrue(live().contactsApi.list().items.none { (it.name ?: "").contains(tag) })
    }

    /**
     * T5-LIVE-28 (suite#288, Calendar): an invitation to the account's alias
     * with RSVP, a repeating event with reminders imported from an `.ics`
     * feed into a new calendar (what a webcal subscription does), holidays
     * and Birthdays & dates read.
     */
    @Test
    fun invitationsAndFeeds() = runBlocking<Unit> {
        val me = own()
        val client = live(store = true)
        val guest = alias(client, me)
        val tag = "cal${System.currentTimeMillis()}"
        val start = java.time.Instant.now().plus(java.time.Duration.ofDays(20)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val made = client.scheduling.create(
            pro.aduki.core.models.EventRequest(title = "Invite $tag", start = start, end = start.plusSeconds(1800), attendees = listOf(guest), reminders = listOf(15)),
            notify = true
        )
        client.sync.flush()
        assertEquals(0, client.sync.pending())
        val hex = withTimeout(10_000) { client.scheduling.appointments!!.first { l -> l.any { it.uid == made.uid && !it.local() } } }.first { it.uid == made.uid }.hex
        val feed = client.scheduling.createCalendar("Feed $tag")
        try {
            client.scheduling.respond(hex, pro.aduki.core.models.Response.Accepted, address = guest)
            assertEquals(0, client.sync.pending())
            client.scheduling.sync()
            val answered = client.scheduling.get(hex)!!
            assertTrue("the answer is stored: ${answered.attendees}", answered.ical.contains("PARTSTAT=ACCEPTED"))
            client.scheduling.reschedule(hex, start.plusSeconds(3600), start.plusSeconds(5400), notify = true)
            client.scheduling.cancel(hex, "live $tag", notify = true)
            assertEquals(0, client.sync.pending())

            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//aduki-kt live//EN
                BEGIN:VEVENT
                UID:feed-$tag@aduki-kt
                DTSTAMP:20261104T000000Z
                DTSTART:${java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(start)}
                DURATION:PT1H
                SUMMARY:Feed $tag
                RRULE:FREQ=DAILY;COUNT=3
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT30M
                END:VALARM
                END:VEVENT
                END:VCALENDAR
            """.trimIndent().replace("\n", "\r\n")
            val imported = client.scheduling.importIcs(ics, feed.hex)
            assertEquals(1, imported.size)
            assertEquals(0, client.sync.pending())
            client.scheduling.sync()
            val occurrences = client.scheduling.expand(start, start.plus(java.time.Duration.ofDays(5))).filter { it.appointment.title == "Feed $tag" }
            assertEquals(3, occurrences.size)
            assertEquals(listOf(30), occurrences.first().appointment.alarms())
            assertTrue(client.schedulingApi.events().any { it.calendar == feed.hex && it.title == "Feed $tag" })
            assertTrue(client.scheduling.exportIcs(listOf(occurrences.first().appointment.hex)).contains("RRULE:FREQ=DAILY;COUNT=3"))

            assertNotNull(client.scheduling.holidayRegions())
            assertNotNull(client.scheduling.holidays(java.time.Instant.now(), java.time.Instant.now().plus(java.time.Duration.ofDays(90))))
            client.scheduling.contactDates()
        } finally {
            client.scheduling.remove(hex, notify = true)
            client.scheduling.removeCalendar(feed.hex)
            client.sync.flush()
            client.close()
        }
    }

    /**
     * T5-LIVE-29 (suite#288, sign-in): "Sign out of all Aduki apps". A sibling
     * opens its own session from a handoff in the signer's device group;
     * its `endDevice()` ends both, and the profile of a switched client is
     * the new session's.
     */
    @Test
    fun signOutOfAllApps() = runBlocking<Unit> {
        val tag = "live-${System.currentTimeMillis()}"
        val handle = System.getenv("ADUKI_LOGIN_HANDLE")
        val password = System.getenv("ADUKI_LOGIN_PASSWORD")
        val secret = System.getenv("ADUKI_LOGIN_TOTP")
        assumeTrue(!handle.isNullOrBlank() && !password.isNullOrBlank() && !secret.isNullOrBlank())
        Thread.sleep(30_000 - System.currentTimeMillis() % 30_000 + 1_000)
        val mailKey = pro.aduki.crypto.dpop.Software.p256()
        val opened = Aduki.signIn(handle!!, password!!, code = totp(secret!!), endpoint = liveEndpoint(), dpop = mailKey, device = "$tag mail", link = true)
        val mail = opened.client
        assertNotNull("a device group", opened.group)
        val profile = mail.account(refresh = true) ?: error("no profile")
        assertEquals(opened.group, profile.group)
        assertTrue(profile.hex.isNotBlank())
        val center = mail.center()
        assertTrue(center.accounts.any { it.hex == profile.hex })

        val calendarKey = pro.aduki.crypto.dpop.Software.p256()
        val code = mail.handoff("pro.aduki.calendar", pro.aduki.net.http.Handoff.BROKER_TTL, calendarKey.thumbprint())
        val calendar = Aduki.exchange(code.code, endpoint = liveEndpoint(), dpop = calendarKey, device = "$tag calendar")
        assertEquals("the sibling joins the signer's group", opened.group, calendar.group)
        assertEquals(profile.hex, calendar.client.account()?.hex)

        // An in-process switch: a client handed another session's tokens reads that session's profile.
        val first = calendar.client.session.tokens.value!!
        val probe = Aduki.builder().endpoint(liveEndpoint()).dpop(calendarKey).token(first.token).build()
        probe.session.update(first)
        assertEquals(first.session, probe.account()?.session)
        assertNotNull(probe.me())
        val again = mail.handoff("pro.aduki.calendar", pro.aduki.net.http.Handoff.BROKER_TTL, calendarKey.thumbprint())
        val second = Aduki.exchange(again.code, endpoint = liveEndpoint(), dpop = calendarKey, device = "$tag calendar 2")
        val next = second.client.session.tokens.value!!
        assertNotEquals(first.session, next.session)
        probe.session.update(next)
        assertNull("the cached identity went with the old tokens", probe.session.identity.value)
        assertEquals("the profile follows the session", next.session, probe.account()?.session)
        assertNotNull(probe.me())
        probe.close()
        second.client.close()

        assertTrue("the group ended", calendar.client.endDevice())
        assertNull(calendar.client.session.tokens.value)
        val renewed = try { mail.refresh() } catch (e: AdukiException.Unauthorized) { assertTrue(e.ended); false }
        assertFalse("the signer's session ended with the group", renewed)
        mail.close()
    }
}
