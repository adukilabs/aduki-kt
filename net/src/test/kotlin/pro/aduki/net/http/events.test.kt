package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.retry.Jitter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EventsTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }

    private fun stream(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream").setBody(body)

    private val rights = "event: rights\ndata: {\"@type\":\"Rights\",\"epoch\":7}\n\n"

    @Test
    fun rightsEventIsDeliveredOnceAndOnlyRightsEvents() {
        server.enqueue(stream(": ping\n\nevent: state\ndata: {\"x\":1}\n\n$rights"))
        val got = CopyOnWriteArrayList<String>()
        val events = Events(OkHttpClient(), server.url("/").toString(), { got += it })

        assertTrue(events.once())
        assertEquals(listOf("{\"@type\":\"Rights\",\"epoch\":7}"), got)
        val request = server.takeRequest()
        assertEquals("/jmap/eventsource?types=*&ping=30", request.path)
        assertTrue(request.getHeader("Accept")!!.contains("text/event-stream"))
    }

    @Test
    fun rightsEventTriggersTheCallbackWithinOneSecondAndReconnectsOnce() {
        server.enqueue(stream(rights))
        server.enqueue(stream(": ping\n\n")) // reconnect: no event, no duplicate
        server.enqueue(MockResponse().setResponseCode(503))
        val latch = CountDownLatch(1)
        val got = CopyOnWriteArrayList<String>()
        val sleeps = CopyOnWriteArrayList<Long>()
        lateinit var events: Events
        events = Events(OkHttpClient(), server.url("/").toString(), { got += it; latch.countDown() },
            Jitter(10, 100)) { ms -> sleeps += ms; if (sleeps.size >= 2) events.stop() }
        val thread = Thread { events.run() }
        val start = System.nanoTime()
        thread.start()

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - start) < 1_000_000_000L)
        thread.join(5_000)
        assertEquals("one renew per event, none after reconnect", 1, got.size)
        assertEquals(3, server.requestCount)
        assertEquals("backoff only after streams that ended without rights", 2, sleeps.size)
        assertTrue(sleeps.all { it in 10..100 })
    }

    @Test
    fun refusedConnectionBacksOffWithJitterThenRecovers() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(stream(rights))
        val sleeps = CopyOnWriteArrayList<Long>()
        val got = CopyOnWriteArrayList<String>()
        lateinit var events: Events
        events = Events(OkHttpClient(), server.url("/").toString(), { got += it; events.stop() },
            Jitter(10, 100)) { sleeps += it }
        events.run()

        assertEquals(1, sleeps.size)
        assertEquals(1, got.size)
    }
}
