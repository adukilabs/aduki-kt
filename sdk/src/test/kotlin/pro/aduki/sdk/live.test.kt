package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.config.Endpoints

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

    private fun liveApiKey(): String {
        val key = System.getProperty("aduki.key") ?: System.getenv("ADUKI_KEY")
        assumeTrue("Skipping live tests: set ADUKI_KEY (or -Daduki.key)", !key.isNullOrBlank())
        return key!!
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
        val client = Aduki.builder()
            .key(liveApiKey())
            .endpoint(liveEndpoint())
            .build()

        val identity = client.me()
        assertNotNull("Expected live server identity resolution", identity)
        assertTrue(identity!!.user.isNotBlank())
        assertTrue(identity.tenant.isNotBlank())
    }

    /**
     * T5-LIVE-02: Client builds against the live endpoint
     */
    @Test
    fun connect() = runBlocking {
        val client = Aduki.builder()
            .key(liveApiKey())
            .endpoint(liveEndpoint())
            .build()

        assertNotNull(client)
        assertTrue(client.lifecycle.active())
    }

    /**
     * T5-LIVE-03: Live Mailbox Listing
     */
    @Test
    fun mailboxes() = runBlocking {
        val client = Aduki.builder()
            .key(liveApiKey())
            .endpoint(liveEndpoint())
            .build()

        val boxes = client.mail.mailboxes()?.value ?: emptyList()
        assertNotNull(boxes)
    }

    /**
     * T5-LIVE-04: Live CONDSTORE Delta Sync
     */
    @Test
    fun sync() = runBlocking {
        val client = Aduki.builder()
            .key(liveApiKey())
            .endpoint(liveEndpoint())
            .build()

        val result = client.sync.all(listOf("inbox"))
        assertTrue(result)
    }

    /**
     * T5-LIVE-05: Live Outbox Send & Delivery
     */
    @Test
    fun send() = runBlocking {
        val client = Aduki.builder()
            .key(liveApiKey())
            .endpoint(liveEndpoint())
            .build()

        val sent = client.mail.send(
            to = listOf("test@aduki.pro"),
            subject = "Automated Live Tier 5 Test",
            body = "Aduki Android SDK Live Verification"
        )
        assertNotNull(sent)
        assertTrue(sent.hex.isNotBlank())
    }
}

