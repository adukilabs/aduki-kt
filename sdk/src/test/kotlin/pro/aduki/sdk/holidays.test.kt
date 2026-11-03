package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentSkipListSet

/** `client.scheduling` holidays: reads never throw, writes throw `AdukiException`. */
class HolidaysTest {

    private lateinit var server: MockWebServer
    private val followed = ConcurrentSkipListSet<String>()
    @Volatile private var down = false
    private var client: Aduki? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (down) return MockResponse().setResponseCode(503)
                val region = path.removePrefix("/v1/user/holidays/")
                return when {
                    path == "/v1/user/holidays" ->
                        ok(followed.joinToString(",", "[", "]") { """{"owner":"o","region":"$it","created":"2026-11-01T00:00:00Z"}""" })
                    request.method == "PUT" && region == "KE" -> {
                        followed += region
                        ok("""{"owner":"o","region":"KE","created":"2026-11-01T00:00:00Z"}""")
                    }
                    request.method == "PUT" -> MockResponse().setResponseCode(400)
                        .setBody("""{"success":false,"error":{"status":400,"kind":"input.invalid","message":"no holiday data for $region"}}""")
                    request.method == "DELETE" && followed.remove(region) -> MockResponse().setResponseCode(204)
                    path.startsWith("/v1/user/exceptions?") -> ok(
                        if (followed.isEmpty()) "[]" else
                            """[{"hex":"h1","span":{"start":"2026-12-11T21:00:00Z","end":"2026-12-12T21:00:00Z"},"kind":"blocked","reason":"KE: Jamhuri Day","source":"holiday","created":"2026-11-01T00:00:00Z"}]"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        client?.close()
        server.shutdown()
    }

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    private fun build(): Aduki = Aduki.builder()
        .key("opaque")
        .endpoint(server.url("/v1").toString())
        .build()
        .also { client = it }

    private val from = Instant.parse("2026-12-01T00:00:00Z")
    private val to = Instant.parse("2027-01-01T00:00:00Z")

    @Test
    fun followReadAndUnfollow() = runBlocking<Unit> {
        val s = build().scheduling
        assertEquals(emptyList<Any>(), s.holidayRegions())
        assertEquals(emptyList<Any>(), s.holidays(from, to))

        assertEquals("KE", s.followHoliday("ke").region)
        assertEquals(listOf("KE"), s.holidayRegions()!!.map { it.region })
        val days = s.holidays(from, to)!!
        assertEquals("Jamhuri Day", days.single().name)
        assertEquals(LocalDate.of(2026, 12, 12), days.single().date(java.time.ZoneId.of("Africa/Nairobi")))

        s.unfollowHoliday("KE")
        assertEquals(emptyList<Any>(), s.holidayRegions())
    }

    @Test
    fun readsReturnNullWhenTheServerFails() = runBlocking<Unit> {
        val s = build().scheduling
        down = true
        assertNull(s.holidayRegions())
        assertNull(s.holidays(from, to))
    }

    @Test
    fun writesThrowAdukiException() = runBlocking<Unit> {
        val s = build().scheduling
        try {
            s.followHoliday("QQ")
            fail("expected a refusal")
        } catch (e: AdukiException.Network) {
            assertEquals(400, e.code)
        }
        try {
            s.unfollowHoliday("UG")
            fail("expected 404")
        } catch (e: AdukiException.Network) {
            assertEquals(404, e.code)
        }
        down = true
        try {
            s.followHoliday("KE")
            fail("expected a failure")
        } catch (e: AdukiException) {
            assertTrue(e is AdukiException.Network)
        }
    }
}
