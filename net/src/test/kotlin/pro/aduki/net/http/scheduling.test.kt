package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
import pro.aduki.core.models.BookRequest
import pro.aduki.core.models.Place
import pro.aduki.core.models.ServiceRequest
import pro.aduki.core.models.Window
import java.time.Instant

/** The scheduling client against the server's events, pages, schedules and bookings routes. */
class SchedulingTest {

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

    private val ical = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:u1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun event(hex: String) = JSONObject()
        .put("hex", hex).put("uid", "u-$hex").put("href", "/caldav/x/$hex.ics").put("etag", "e-$hex")
        .put("ical", ical).put("kind", "VEVENT").put("start", "2026-11-01T09:00:00Z").put("end", "2026-11-01T10:00:00Z")
        .put("summary", "Standup").put("description", JSONObject.NULL).put("location", "HQ")
        .put("attendees", JSONArray(listOf("bob@example.com"))).put("organizer", JSONObject.NULL)
        .put("status", JSONObject.NULL).put("sequence", 2).put("rrule", "FREQ=DAILY").put("recurring", true)
        .put("timezone", JSONObject.NULL).put("created", "2026-10-24T15:48:47Z").put("updated", "2026-10-24T15:48:47Z")
        .toString()

    @Test
    fun eventsReadsEveryPage() {
        reply("""{"items":[${event("a")}],"total":2,"page":1,"pages":2}""")
        reply("""{"items":[${event("b")}],"total":2,"page":2,"pages":2}""")
        reply("""{"items":[],"total":2,"page":3,"pages":2}""") // never asked

        val list = api.events()

        assertEquals(listOf("a", "b"), list.map { it.hex })
        val first = list[0]
        assertEquals("Standup", first.title)
        assertEquals("", first.notes)
        assertEquals("UTC", first.timezone)
        assertEquals(listOf("bob@example.com"), first.attendees)
        assertEquals(2, first.sequence)
        assertEquals("FREQ=DAILY", first.rrule)
        assertEquals(ical, first.ical)
        assertEquals("/v1/user/events?page=1&limit=200", server.takeRequest().path)
        assertEquals("/v1/user/events?page=2&limit=200", server.takeRequest().path)
    }

    // Replies recorded from the live server (scheduling/*.json, account hex replaced).
    private fun recorded(name: String) =
        server.enqueue(MockResponse().setBody(javaClass.getResource("/scheduling/$name")!!.readText()))

    @Test
    fun recordedEventsParse() {
        recorded("events.json")

        val list = api.events()

        assertEquals(2, list.size)
        val holiday = list.first { it.uid == "fixture-2@aduki" }
        // The server reads no times from an all-day object (DTSTART;VALUE=DATE).
        assertEquals("", holiday.start)
        assertTrue(holiday.ical.contains("DTSTART;VALUE=DATE:20261105"))
        val standup = list.first { it.uid == "fixture-1@aduki" }
        assertEquals("2026-11-02T06:00:00Z", standup.start)
        assertEquals("Africa/Nairobi", standup.timezone)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6", standup.rrule)
        assertTrue(standup.recurring)
        assertEquals(listOf("bob@example.com"), standup.attendees)
        assertTrue(standup.ical.contains("BEGIN:VALARM"))
    }

    @Test
    fun recordedScheduleAndBookings() {
        recorded("schedule.json")
        recorded("bookings.json")

        assertEquals("39ff21cbd4760d8e", api.createSchedule("sdk-fixture", "Africa/Nairobi", listOf(Window(listOf("mon"), "09:00", "17:00"))))
        assertEquals(emptyList<Any>(), api.bookings(upcoming = true))
        server.takeRequest()
        assertEquals("/v1/user/bookings?upcoming=true&page=1&limit=200", server.takeRequest().path)
    }

    @Test
    fun rangeIsAPathOfTwoTimes() {
        reply("""{"items":[],"total":0,"page":1,"pages":0}""")

        api.range(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"))

        assertEquals(
            "/v1/user/events/range/2026-11-01T00:00:00Z/2026-11-02T00:00:00Z?page=1&limit=200",
            server.takeRequest().path
        )
    }

    @Test
    fun eventWritesSendTheWholeObject() {
        reply(event("n"), 201)
        reply(event("n"))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals("n", api.createEvent(ical, "cal1").hex)
        api.replaceEvent("n", ical)
        api.removeEvent("n")

        val create = server.takeRequest()
        assertEquals("POST /v1/user/events", "${create.method} ${create.path}")
        val body = JSONObject(create.body.readUtf8())
        assertEquals(ical, body.getString("ical"))
        assertEquals("cal1", body.getString("calendar"))
        val edit = server.takeRequest()
        assertEquals("PATCH /v1/user/events/n", "${edit.method} ${edit.path}")
        assertEquals(setOf("ical"), JSONObject(edit.body.readUtf8()).keySet())
        val delete = server.takeRequest()
        assertEquals("DELETE /v1/user/events/n", "${delete.method} ${delete.path}")
    }

    @Test
    fun aReplyWithoutAHexIsRefused() {
        reply("""{"uid":"x"}""", 201)
        try {
            api.createEvent(ical)
            fail("expected Network")
        } catch (_: AdukiException.Network) {
        }
    }

    @Test
    fun errorsCarryTheStatus() {
        failure(409, "conflict")
        try {
            api.createEvent(ical)
            fail("expected Network")
        } catch (e: AdukiException.Network) {
            assertEquals(409, e.code)
            assertTrue(e.message!!.contains("conflict"))
        }
        failure(401, "auth.required")
        try {
            api.events()
            fail("expected Auth")
        } catch (_: AdukiException.Auth) {
        }
    }

    @Test
    fun proposedRoutesAreNullWhereTheServerLacksThem() {
        failure(404, "not.found")
        assertNull(api.reply("n", "ACCEPTED"))
        val asked = server.takeRequest()
        assertEquals("POST /v1/user/events/n/reply", "${asked.method} ${asked.path}")
        assertEquals("ACCEPTED", JSONObject(asked.body.readUtf8()).getString("partstat"))

        failure(405, "method")
        assertNull(api.stats("p1", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")))
        assertEquals(
            "/v1/user/pages/p1/stats?from=2026-11-01T00%3A00%3A00Z&to=2026-12-01T00%3A00%3A00Z",
            server.takeRequest().path
        )

        reply("""{"views":12,"bookings":3,"confirmed":2,"pending":0,"cancelled":1,"done":0,"noshow":0}""")
        val stats = api.stats("p1", Instant.EPOCH, Instant.parse("2026-12-01T00:00:00Z"))!!
        assertEquals(12, stats.views)
        assertEquals(1, stats.cancelled)
        assertEquals("p1", stats.service)
    }

    private val page = """{"hex":"p1","slug":"consult","name":"Consult","about":null,"lengths":[45,60],"before":5,
        "after":10,"notice":120,"horizon":30,"step":15,"daily":null,"weekly":null,"mode":"solo","seats":1,
        "visibility":"link","confirm":"manual","tentative":false,"uses":null,"place":{"kind":"video","value":"https://m/x"},
        "questions":[{"q":"Company?"}],"remind":[60],"paused":null,"created":"c","updated":"u","team":null}"""

    @Test
    fun servicesArePages() {
        reply("[$page]")

        val list = api.services()

        assertEquals("/v1/user/pages", server.takeRequest().path)
        val s = list.single()
        assertEquals("consult", s.slug)
        assertEquals(45, s.duration)
        assertEquals(listOf(45, 60), s.lengths)
        assertEquals(5, s.before)
        assertEquals(10, s.after)
        assertEquals(15, s.increment)
        assertEquals("", s.description)
        assertEquals(Place("video", "https://m/x"), s.place)
        assertEquals(listOf(60), s.reminders)
        assertTrue(s.active)
        assertEquals("", s.schedule)
    }

    @Test
    fun aPausedPageIsInactive() {
        reply(page.replace("\"paused\":null", "\"paused\":\"2026-10-24T00:00:00Z\"").replace("\"remind\":[60]", "\"remind\":null"))
        val s = api.service("p1")
        assertFalse(s.active)
        assertNull(s.reminders)
        assertEquals("/v1/user/pages/p1", server.takeRequest().path)
    }

    @Test
    fun serviceWritesSendOnlyWhatIsSet() {
        reply(page, 201)
        reply(page)
        server.enqueue(MockResponse().setResponseCode(204))

        api.createService(
            ServiceRequest(
                name = "Consult", slug = "consult", lengths = listOf(45), after = 10, increment = 15,
                place = Place("video", "https://m/x"), questions = """[{"q":"Company?"}]""", reminders = emptyList()
            ),
            schedule = "s1"
        )
        api.editService("p1", ServiceRequest(paused = true, description = ""))
        api.removeService("p1")

        val create = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(
            setOf("schedule", "name", "slug", "lengths", "after", "step", "place", "questions", "remind"),
            create.keySet()
        )
        assertEquals("s1", create.getString("schedule"))
        assertEquals(0, create.getJSONArray("remind").length())
        val edit = server.takeRequest()
        assertEquals("PATCH /v1/user/pages/p1", "${edit.method} ${edit.path}")
        val patch = JSONObject(edit.body.readUtf8())
        assertEquals(setOf("paused", "about"), patch.keySet())
        assertTrue(patch.isNull("about"))
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun windowsAreScheduleRules() {
        reply("""{"hex":"s9","name":"consult","zone":"Africa/Nairobi","main":false,"rules":[],"template":null}""", 201)
        reply("[]")
        reply("""[{"hex":"s9","name":"consult","zone":"UTC","main":false}]""")
        val windows = listOf(Window(listOf("Mon", "tue"), "09:00", "17:00"))

        assertEquals("s9", api.createSchedule("consult", "Africa/Nairobi", windows))
        api.rules("s9", windows)
        assertEquals(listOf("s9" to "consult"), api.schedules())

        val made = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("Africa/Nairobi", made.getString("zone"))
        assertEquals("mon", made.getJSONArray("rules").getJSONObject(0).getJSONArray("days").getString(0))
        val put = server.takeRequest()
        assertEquals("PUT /v1/user/schedules/s9/rules", "${put.method} ${put.path}")
        assertEquals("17:00", JSONArray(put.body.readUtf8()).getJSONObject(0).getString("finishes"))
    }

    @Test
    fun slotsAndBookings() {
        reply("""[{"start":"2026-11-02T09:00:00Z","end":"2026-11-02T09:45:00Z"}]""")
        reply(
            """{"booking":{"hex":"b1","span":{"start":"2026-11-02T09:00:00Z","end":"2026-11-02T09:45:00Z"},"zone":"UTC",
               "state":"confirmed","uid":"u","sequence":0,"place":null,"notes":null,"cancelled":null,"created":"c",
               "updated":"u","reason":null},"token":"tok","charge":null}""",
            201
        )
        reply("""{"items":[{"hex":"b1","span":{"start":"s","end":"e"},"zone":"UTC","state":"cancelled","uid":"u","sequence":1,"reason":"guest"}],"total":1,"page":1,"pages":1}""")

        val slots = api.slots("p1", Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"), 45)
        val booked = api.book("p1", BookRequest(start = Instant.parse("2026-11-02T09:00:00Z"), name = "Ann", email = "ann@example.com", answers = mapOf("q" to "a")))
        val list = api.bookings(upcoming = true)

        assertEquals("2026-11-02T09:45:00Z", slots.single().end)
        assertEquals(
            "/v1/user/book/pages/p1/slots?from=2026-11-02T00%3A00%3A00Z&to=2026-11-03T00%3A00%3A00Z&length=45",
            server.takeRequest().path
        )
        assertEquals("b1", booked.hex)
        assertEquals("tok", booked.token)
        assertEquals("2026-11-02T09:00:00Z", booked.start)
        val book = server.takeRequest()
        assertEquals("POST /v1/book/pages/p1/bookings", "${book.method} ${book.path}")
        val body = JSONObject(book.body.readUtf8())
        assertEquals("ann@example.com", body.getString("email"))
        assertEquals("a", body.getJSONObject("answers").getString("q"))
        assertFalse(body.has("length"))
        assertEquals("guest", list.single().reason)
        assertEquals("/v1/user/bookings?upcoming=true&page=1&limit=200", server.takeRequest().path)
    }

    @Test
    fun bookingsAreReadAndSettled() {
        val booking = """{"hex":"b1","span":{"start":"2026-11-02T09:00:00Z","end":"2026-11-02T09:30:00Z"},"zone":"Africa/Nairobi",
            "state":"pending","uid":"u1","sequence":0,"place":{"kind":"video","value":"https://m/x"},"notes":"hi",
            "cancelled":null,"created":"c","updated":"u","reason":null}"""
        reply(booking.dropLast(1) + ""","guests":[{"hex":"g1","name":"Ann","email":"ann@example.com","phone":null,"rsvp":"invited","answers":{},"notified":null,"created":"c"}]}""")
        reply(booking.replace("pending", "cancelled"))
        reply(booking, 201)
        reply("""[{"hex":"o1","slug":"intro","name":"Intro","about":null,"length":45,"before":null,"after":5,"capacity":null,
            "price":250000,"currency":"KES","tax":1600,"inclusive":true,"deposit":null,"hold":15,"refund_hours":24,
            "refund_percent":50,"questions":[],"paused":null,"created":"c","updated":"u","page":"p1","quote":null}]""")

        val one = api.booking("b1")
        val cancelled = api.updateBooking("b1", "cancelled")
        api.reserve("p1", BookRequest(start = Instant.parse("2026-11-02T09:00:00Z"), zone = "Africa/Nairobi", offer = "o1"))
        val offers = api.offers("p1")

        assertEquals("ann@example.com", one.guests.single().email)
        assertEquals(Place("video", "https://m/x"), one.place)
        assertEquals("2026-11-02T09:30:00Z", one.end)
        assertEquals("cancelled", cancelled.state)
        assertEquals("/v1/user/bookings/b1", server.takeRequest().path)
        val patch = server.takeRequest()
        assertEquals("PATCH /v1/user/bookings/b1", "${patch.method} ${patch.path}")
        assertEquals("""{"state":"cancelled"}""", patch.body.readUtf8())
        val reserve = server.takeRequest()
        assertEquals("POST /v1/user/book/pages/p1/bookings", "${reserve.method} ${reserve.path}")
        val body = JSONObject(reserve.body.readUtf8())
        assertEquals(setOf("start", "zone", "answers", "service"), body.keySet())
        assertEquals("o1", body.getString("service"))
        assertEquals("/v1/user/schedule/services?page=p1", server.takeRequest().path)
        val offer = offers.single()
        assertEquals("p1", offer.page)
        assertEquals(45, offer.length)
        assertNull(offer.before)
        assertEquals(5, offer.after)
        assertEquals(250000L, offer.price)
    }
}
