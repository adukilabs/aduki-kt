package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import java.time.Instant
import java.time.LocalDate

/** The scheduling client against `/user/holidays` and the holiday exceptions. */
class HolidaysTest {

    private lateinit var server: MockWebServer
    private lateinit var api: Scheduling

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Scheduling(OkHttpClient(), server.url("/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(data: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}"""))
    }

    private fun failure(code: Int, kind: String) {
        server.enqueue(
            MockResponse().setResponseCode(code)
                .setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"no"}}""")
        )
    }

    private fun exception(hex: String, start: String, end: String, reason: String?, source: String) =
        """{"hex":"$hex","span":{"start":"$start","end":"$end"},"kind":"blocked",
           "reason":${reason?.let { "\"$it\"" } ?: "null"},"source":"$source","created":"2026-11-01T00:00:00Z"}"""

    @Test
    fun listsFollowedRegions() {
        reply("""[{"owner":"0b0c","region":"KE","created":"2026-11-01T08:00:00Z"},{"owner":"0b0c","region":"ug","created":"2026-11-02T08:00:00Z"}]""")
        val regions = api.holidayRegions()
        assertEquals(listOf("KE", "UG"), regions.map { it.region })
        assertEquals("2026-11-01T08:00:00Z", regions[0].created)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/user/holidays", req.path)
    }

    @Test
    fun aNewAccountFollowsNone() {
        reply("[]")
        assertTrue(api.holidayRegions().isEmpty())
    }

    @Test
    fun followsAndUnfollowsByUpperCaseCode() {
        reply("""{"owner":"0b0c","region":"KE","created":"2026-11-01T08:00:00Z"}""")
        assertEquals("KE", api.followHoliday(" ke ").region)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/user/holidays/KE", put.path)

        server.enqueue(MockResponse().setResponseCode(204))
        api.unfollowHoliday("ke")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/user/holidays/KE", delete.path)
    }

    @Test
    fun aRegionWithoutDataIsRefused() {
        failure(400, "input.invalid")
        try {
            api.followHoliday("QQ")
            fail("expected a refusal")
        } catch (e: AdukiException.Network) {
            assertEquals(400, e.code)
        }
    }

    @Test
    fun unfollowingARegionNotFollowedIs404() {
        failure(404, "not_found")
        try {
            api.unfollowHoliday("KE")
            fail("expected 404")
        } catch (e: AdukiException.Network) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun holidaysAreTheHolidayExceptionsOnly() {
        reply(
            "[" + listOf(
                exception("h1", "2026-12-11T21:00:00Z", "2026-12-12T21:00:00Z", "KE: Jamhuri Day", "holiday"),
                exception("m1", "2026-12-14T09:00:00Z", "2026-12-14T10:00:00Z", "Dentist", "manual"),
                exception("h2", "2026-12-25T00:00:00Z", "2026-12-26T00:00:00Z", "Founders' Day", "holiday")
            ).joinToString(",") + "]"
        )
        val from = Instant.parse("2026-12-01T00:00:00Z")
        val to = Instant.parse("2027-01-01T00:00:00Z")
        val days = api.holidays(from, to)
        assertEquals(listOf("h1", "h2"), days.map { it.hex })
        assertEquals("Jamhuri Day", days[0].name)
        assertEquals("KE", days[0].region)
        assertEquals(LocalDate.of(2026, 12, 12), days[0].date)
        // A tenant's own day has no region prefix.
        assertEquals("Founders' Day", days[1].name)
        assertEquals("", days[1].region)
        assertEquals(LocalDate.of(2026, 12, 25), days[1].date)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/user/exceptions", url.encodedPath)
        assertEquals("2026-12-01T00:00:00Z", url.queryParameter("from"))
        assertEquals("2027-01-01T00:00:00Z", url.queryParameter("to"))
    }

    @Test
    fun aRangeLongerThanTheServerWindowIsReadInParts() {
        reply("[" + exception("h1", "2027-01-01T00:00:00Z", "2027-01-02T00:00:00Z", "KE: New Year's Day", "holiday") + "]")
        reply("[" + exception("h1", "2027-01-01T00:00:00Z", "2027-01-02T00:00:00Z", "KE: New Year's Day", "holiday") + "]")
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2027-06-01T00:00:00Z")
        val days = api.holidays(from, to)
        assertEquals(1, days.size)
        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertEquals(from.toString(), first.queryParameter("from"))
        assertEquals(first.queryParameter("to"), second.queryParameter("from"))
        assertEquals(to.toString(), second.queryParameter("to"))
        assertEquals(366L * 86400, Instant.parse(first.queryParameter("to")).epochSecond - from.epochSecond)
    }

    @Test
    fun anEmptyRangeMakesNoCall() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(api.holidays(at, at).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theDateIsTheMiddleOfTheDayInAnyZone() {
        // Local midnight to midnight in Honolulu (UTC-10) and Auckland (UTC+13 in summer).
        val hnl = pro.aduki.core.models.Holiday(start = "2026-07-04T10:00:00Z", end = "2026-07-05T10:00:00Z")
        assertEquals(LocalDate.of(2026, 7, 4), hnl.date)
        val akl = pro.aduki.core.models.Holiday(start = "2026-02-05T11:00:00Z", end = "2026-02-06T11:00:00Z")
        // UTC+13 is past the UTC reading; the zone it is shown in reads it right.
        assertEquals(LocalDate.of(2026, 2, 6), akl.date(java.time.ZoneId.of("Pacific/Auckland")))
        assertEquals(LocalDate.of(2026, 2, 6), akl.date(java.time.ZoneId.of("Australia/Sydney")))
        assertEquals(null, pro.aduki.core.models.Holiday(start = "x").date)
    }
}
