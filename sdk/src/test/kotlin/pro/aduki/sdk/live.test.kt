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
import pro.aduki.core.models.Card

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
     * T5-LIVE-07: Contact create, update, groups and delete through the outbox; removes what it made.
     */
    @Test
    fun contacts() = runBlocking {
        val client = live(store = true)
        val tag = "sdk-live-${System.currentTimeMillis()}"
        var hex = ""
        try {
            val made = client.contacts.create(
                Card(name = "SDK Live $tag", emails = listOf("$tag@example.com"), phones = listOf("+15550100"), company = "Aduki", notes = "live test")
            )
            assertEquals(0, client.sync.pending())
            hex = client.contacts.observe()!!.value.firstOrNull { it.uid == made.uid }?.hex
                ?: withTimeout(5_000) { client.contacts.observe()!!.first { list -> list.any { it.uid == made.uid } } }.first { it.uid == made.uid }.hex
            assertTrue("expected the server's hex, got $hex", !hex.startsWith("contact_"))

            client.contacts.update(hex, client.contacts.card(hex)!!.copy(name = "SDK Live Edited $tag", phones = listOf("+15550101")))
            client.contacts.groups.add(tag, listOf(hex))
            assertEquals(0, client.sync.pending())

            val row = client.contactsApi.list().items.first { it.hex == hex }
            assertEquals("SDK Live Edited $tag", row.name)
            assertEquals(listOf("+15550101"), row.phones)
            assertEquals(listOf(tag), row.groups)
            assertTrue(row.vcard.contains("ORG:Aduki"))

            assertTrue(client.contacts.sync())
            assertEquals("SDK Live Edited $tag", client.contacts.get(hex)?.name)

            client.contacts.delete(hex)
            assertEquals(0, client.sync.pending())
            assertTrue(client.contactsApi.list().items.none { it.hex == hex })
            hex = ""
        } finally {
            if (hex.isNotBlank() && !hex.startsWith("contact_")) runCatching { client.contactsApi.delete(hex) }
            client.close()
        }
    }
}
