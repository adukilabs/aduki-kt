package pro.aduki.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.calendar.Events
import pro.aduki.core.models.EventChanges
import pro.aduki.core.models.EventRequest
import pro.aduki.core.models.Response
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Events offline-first through the store, the outbox and `/user/events` (#40, #42, #48). */
class CalendarTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val calls = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val held = ConcurrentHashMap<String, String>() // hex -> ical
    @Volatile private var down = false
    @Volatile private var recorded = false
    private var client: Aduki? = null

    private val nine = Instant.parse("2026-11-02T06:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                calls += "${request.method} $path"
                if (body.isNotBlank()) bodies += body
                if (down) return MockResponse().setResponseCode(503)
                val hex = path.removePrefix("/v1/user/events/").substringBefore('/').substringBefore('?')
                return when {
                    path == "/v1/user" -> ok("""{"hex":"u1","tenant":"t1","email":"me@example.com"}""")
                    recorded && path.startsWith("/v1/user/events?") ->
                        MockResponse().setBody(javaClass.getResource("/scheduling/events.json")!!.readText())
                    path.startsWith("/v1/user/events?") -> ok(
                        """{"items":[${held.entries.joinToString(",") { event(it.key, it.value) }}],"total":${held.size},"page":1,"pages":1}"""
                    )
                    request.method == "POST" && path == "/v1/user/events" -> {
                        val made = "srv${held.size + 1}"
                        held[made] = JSONObject(body).getString("ical")
                        ok(event(made, held[made]!!), 201)
                    }
                    // Like the server: only an event with an organizer is answered.
                    path.endsWith("/reply") && held[hex]?.contains("ORGANIZER") == true -> ok(event(hex, held[hex]!!))
                    path.endsWith("/reply") -> MockResponse().setResponseCode(422)
                        .setBody("""{"success":false,"error":{"status":422,"kind":"input.invalid","message":"the event has no organizer"}}""")
                    path == "/v1/user/schedules" && request.method == "POST" ->
                        ok(javaClass.getResource("/scheduling/schedule.json")!!.readText().let { JSONObject(it).getJSONObject("data").toString() }, 201)
                    path == "/v1/user/schedules" -> ok("""[{"hex":"39ff21cbd4760d8e","name":"consult","zone":"UTC","main":false}]""")
                    path.startsWith("/v1/user/schedules/") -> ok("[]")
                    path.startsWith("/v1/user/pages/p1/stats") ->
                        ok("""{"views":7,"bookings":2,"confirmed":1,"pending":0,"cancelled":1,"done":0,"noshow":0}""")
                    path.startsWith("/v1/user/pages") -> ok(PAGE, if (request.method == "POST") 201 else 200)
                    path.startsWith("/v1/user/bookings") -> ok(
                        """{"items":[{"hex":"b1","span":{"start":"2026-11-02T09:00:00Z","end":"2026-11-02T09:30:00Z"},"state":"cancelled"},
                           {"hex":"b2","span":{"start":"2026-11-03T09:00:00Z","end":"2026-11-03T09:30:00Z"},"state":"confirmed"},
                           {"hex":"b3","span":{"start":"2026-12-03T09:00:00Z","end":"2026-12-03T09:30:00Z"},"state":"confirmed"}],"total":3}"""
                    )
                    request.method == "PATCH" && held.containsKey(hex) -> {
                        held[hex] = JSONObject(body).getString("ical")
                        ok(event(hex, held[hex]!!))
                    }
                    request.method == "DELETE" && held.remove(hex) != null -> MockResponse().setResponseCode(204)
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

    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")

    private fun event(hex: String, ical: String): String {
        val e = Events.read(ical)!!
        return JSONObject().put("hex", hex).put("uid", e.uid).put("etag", "e${ical.hashCode()}").put("ical", ical)
            .put("summary", e.title).put("sequence", e.sequence).toString()
    }

    private fun build(): Aduki = Aduki.builder()
        .key("opaque")
        .endpoint(server.url("/v1").toString())
        .store(dir.newFolder("db"))
        .build()
        .also { client = it }

    private fun standup() = EventRequest(
        title = "Standup", start = nine, end = nine.plusSeconds(1800), timezone = "Africa/Nairobi",
        attendees = listOf("me@example.com", "bob@example.com"), rrule = "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4", reminders = listOf(10)
    )

    @Test
    fun writesWithoutAStoreThrow() = runBlocking<Unit> {
        val bare = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).build()
        for (write in listOf<suspend () -> Unit>(
            { bare.scheduling.create(standup()) },
            { bare.scheduling.update("h", EventChanges(title = "x")) },
            { bare.scheduling.reschedule("h", nine, nine.plusSeconds(60)) },
            { bare.scheduling.respond("h", Response.Accepted, "a@example.com") },
            { bare.scheduling.cancel("h") },
            { bare.scheduling.remove("h") },
            { bare.scheduling.importIcs("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n") }
        )) {
            val e = runCatching { write() }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
            assertEquals("no local store", e?.message)
        }
        assertNull(bare.scheduling.observe(nine, nine.plusSeconds(60)))
    }

    @Test
    fun anEventMadeOfflineIsSentLaterAndTakesTheServerHex() = runBlocking<Unit> {
        val aduki = build()
        down = true

        val made = aduki.scheduling.create(standup())

        assertTrue(made.hex.startsWith("local-"))
        assertEquals(1, aduki.sync.outbox.value)
        val week = aduki.scheduling.observe(nine, nine.plusSeconds(7 * 86_400))!!
        val shown = withTimeout(5_000) { week.first { it.size == 2 } }
        assertEquals(listOf(nine.toEpochMilli(), nine.plusSeconds(2 * 86_400).toEpochMilli()), shown.map { it.start })

        // A reschedule while offline is queued behind the create.
        aduki.scheduling.reschedule(made.hex, nine.plusSeconds(3600), nine.plusSeconds(5400))
        assertEquals(2, aduki.sync.outbox.value)

        down = false
        Thread.sleep(1_500) // past the first retry delay
        aduki.sync.flush()

        assertEquals(0, aduki.sync.outbox.value)
        val now = aduki.scheduling.get("srv1")!!
        assertEquals(nine.plusSeconds(3600).toEpochMilli(), now.start)
        assertEquals(nine.toEpochMilli(), now.moved)
        assertTrue(calls.contains("POST /v1/user/events"))
        assertTrue(calls.contains("PATCH /v1/user/events/srv1"))
        val sent = Events.read(held["srv1"]!!)!!
        assertEquals(1, sent.sequence)
        assertEquals(listOf(10), sent.reminders) // whole object, alarms kept
        assertEquals(2, sent.attendees.size)
    }

    @Test
    fun respondCancelAndRemoveGoOutOnce() = runBlocking<Unit> {
        val aduki = build()
        val hex = aduki.scheduling.create(standup()).let { aduki.scheduling.get("srv1")!!.hex }
        calls.clear()

        aduki.scheduling.respond(hex, Response.Declined)
        assertEquals("DECLINED", aduki.scheduling.get(hex)!!.guests().first { it.first == "me@example.com" }.second)
        // No organizer, so the server will not answer (422): the object goes out with PATCH.
        assertEquals(listOf("GET /v1/user", "POST /v1/user/events/srv1/reply", "PATCH /v1/user/events/srv1"), calls)
        assertTrue(held["srv1"]!!.contains("PARTSTAT=DECLINED"))

        calls.clear()
        val cancelled = aduki.scheduling.cancel(hex, "ill")
        assertEquals("cancelled", cancelled.status)
        assertEquals(listOf("PATCH /v1/user/events/srv1"), calls) // once, through the outbox
        assertEquals("CANCELLED", Events.read(held["srv1"]!!)!!.status)

        calls.clear()
        aduki.scheduling.remove(hex)
        assertNull(aduki.scheduling.get(hex))
        assertEquals(listOf("DELETE /v1/user/events/srv1"), calls)
        assertEquals(0, aduki.sync.outbox.value)
    }

    @Test
    fun anAnswerToAnOrganizedEventGoesToTheReplyRouteWithItsComment() = runBlocking<Unit> {
        val aduki = build()
        val organized = Events.build(standup()).replace("BEGIN:VEVENT\r\n", "BEGIN:VEVENT\r\nORGANIZER:mailto:boss@example.com\r\n")
        val hex = aduki.scheduling.importIcs(organized).single().let { aduki.scheduling.get("srv1")!!.hex }
        calls.clear()
        bodies.clear()

        aduki.scheduling.respond(hex, Response.Tentative, comment = "maybe")

        assertEquals(listOf("GET /v1/user", "POST /v1/user/events/srv1/reply"), calls)
        val body = JSONObject(bodies.single())
        assertEquals("TENTATIVE", body.getString("partstat"))
        assertEquals("maybe", body.getString("comment"))
        assertEquals(0, aduki.sync.outbox.value)
    }

    @Test
    fun notifyGoesWithCreateUpdateCancelAndRemove() = runBlocking<Unit> {
        val aduki = build()
        aduki.scheduling.create(standup(), notify = true)
        assertTrue(JSONObject(bodies.last()).getBoolean("notify"))
        aduki.scheduling.update("srv1", EventChanges(title = "Sync"), notify = true)
        assertTrue(JSONObject(bodies.last()).getBoolean("notify"))
        aduki.scheduling.cancel("srv1", notify = true)
        assertTrue(JSONObject(bodies.last()).getBoolean("notify"))
        aduki.scheduling.update("srv1", EventChanges(title = "Quiet"))
        assertFalse(JSONObject(bodies.last()).has("notify"))
        aduki.scheduling.remove("srv1", notify = true)
        assertEquals("DELETE /v1/user/events/srv1?notify=true", calls.last())
        assertEquals(0, aduki.sync.outbox.value)
    }

    @Test
    fun removingAnEventNeverSentForgetsItsCreate() = runBlocking<Unit> {
        val aduki = build()
        down = true
        val made = aduki.scheduling.create(standup())
        aduki.scheduling.remove(made.hex)

        assertEquals(0, aduki.sync.outbox.value)
        calls.clear()
        down = false
        aduki.sync.flush()
        assertFalse(calls.any { it.startsWith("POST /v1/user/events") || it.startsWith("DELETE") })
    }

    @Test
    fun syncLoadsRecordedEventsAndKeepsQueuedEdits() = runBlocking<Unit> {
        val aduki = build()
        recorded = true

        assertEquals(2, aduki.scheduling.sync())

        val holiday = aduki.scheduling.appointments!!.let { flow -> withTimeout(5_000) { flow.first { it.size == 2 } } }
            .first { it.uid == "fixture-2@aduki" }
        // The server sent no times for the all-day event: they come from its object.
        assertTrue(holiday.allday)
        assertEquals(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), holiday.start)
        val standup = aduki.scheduling.get("c56db59cd247655c")!!
        assertEquals("Africa/Nairobi", standup.timezone)
        assertEquals("bob@example.com=NEEDS-ACTION", standup.attendees)
        // Six weekly occurrences, the server matches only the first by range.
        assertEquals(6, aduki.scheduling.expand(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))
            .count { it.appointment.hex == standup.hex })

        down = true
        aduki.scheduling.update(standup.hex, EventChanges(title = "Renamed"))
        assertEquals(1, aduki.sync.outbox.value)
        down = false
        Thread.sleep(1_500) // past the first retry delay
        aduki.scheduling.sync() // flushes first; the PATCH 404s on this fake and completes
        assertEquals(0, aduki.sync.outbox.value)
    }

    @Test
    fun icsImportsAndExports() = runBlocking<Unit> {
        val aduki = build()
        val file = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:a@x\r\nDTSTART:20261102T090000Z\r\nDTEND:20261102T100000Z\r\nSUMMARY:A\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:b@x\r\nDTSTART;VALUE=DATE:20261103\r\nSUMMARY:B\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

        val made = aduki.scheduling.importIcs(file)

        assertEquals(listOf("A", "B"), made.map { it.title })
        assertEquals(2, calls.count { it == "POST /v1/user/events" })
        val again = aduki.scheduling.importIcs(file.replace("SUMMARY:A", "SUMMARY:A2"))
        assertEquals("A2", again[0].title)
        assertEquals(2, calls.count { it == "POST /v1/user/events" }) // known UIDs are replaced
        val out = aduki.scheduling.exportIcs()
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(out).count())
        assertTrue(out.contains("SUMMARY:A2"))
    }

    @Test
    fun servicesArePagesWithTheirOwnSchedule() = runBlocking<Unit> {
        val aduki = build()
        val windows = listOf(pro.aduki.core.models.Window(listOf("mon", "fri"), "09:00", "12:00"))

        val made = aduki.scheduling.createService(
            pro.aduki.core.models.ServiceRequest(name = "Consult", slug = "consult", lengths = listOf(30), windows = windows, zone = "Africa/Nairobi")
        )
        assertEquals(listOf("POST /v1/user/schedules", "POST /v1/user/pages"), calls)
        assertEquals("39ff21cbd4760d8e", JSONObject(bodies[1]).getString("schedule"))
        assertEquals("http://x/v1/book/pages/p1", aduki.scheduling.bookingUrl(made, "http://x/v1/"))

        calls.clear()
        aduki.scheduling.updateService("p1", pro.aduki.core.models.ServiceRequest(windows = windows, paused = true))
        // The schedule named after the slug gets the new windows; the page is only paused.
        assertEquals(
            listOf("GET /v1/user/pages/p1", "GET /v1/user/schedules", "PUT /v1/user/schedules/39ff21cbd4760d8e/rules", "PATCH /v1/user/pages/p1"),
            calls
        )
        assertEquals(setOf("paused"), JSONObject(bodies.last()).keySet())

        val stats = aduki.scheduling.stats("p1", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))
        assertEquals(2, stats.bookings)
        assertEquals(1, stats.cancelled)
        assertEquals("p1", stats.service)
        assertEquals(7, stats.views)
        assertEquals("GET /v1/user/pages/p1/stats?from=2026-11-01T00%3A00%3A00Z&to=2026-12-01T00%3A00%3A00Z", calls.last())
    }

    @Test
    fun editOrCancelOneOccurrenceOfRepeatingEvent() = runBlocking<Unit> {
        val aduki = build()
        aduki.scheduling.create(
            EventRequest(
                title = "Weekly Standup",
                start = nine, // Mon Nov 2 2026 06:00 UTC (09:00 Nairobi)
                end = nine.plusSeconds(3600),
                timezone = "Africa/Nairobi",
                rrule = "FREQ=WEEKLY;COUNT=4"
            )
        )
        val hex = aduki.scheduling.get("srv1")!!.hex

        val from = nine.minusSeconds(3600)
        val to = nine.plusSeconds(28 * 86_400)
        var occurrences = aduki.scheduling.expand(from, to)
        assertEquals(4, occurrences.size)
        assertEquals("Weekly Standup", occurrences[0].appointment.title)
        assertEquals("Weekly Standup", occurrences[1].appointment.title)

        // 1. Edit the 2nd occurrence (Mon Nov 9)
        val week2 = nine.plusSeconds(7 * 86_400)
        calls.clear()
        aduki.scheduling.updateOccurrence(hex, week2, EventChanges(title = "Standup with Guest", location = "Room 101"), notify = true)
        assertTrue(calls.contains("PATCH /v1/user/events/srv1"))
        assertTrue(held["srv1"]!!.contains("RECURRENCE-ID;TZID=Africa/Nairobi:20261109T090000"))

        occurrences = aduki.scheduling.expand(from, to)
        assertEquals(4, occurrences.size)
        assertEquals("Weekly Standup", occurrences[0].appointment.title)
        assertEquals("Standup with Guest", occurrences[1].appointment.title)
        assertEquals("Room 101", occurrences[1].appointment.location)
        assertEquals("Weekly Standup", occurrences[2].appointment.title)

        // 2. Cancel the 3rd occurrence (Mon Nov 16)
        val week3 = nine.plusSeconds(14 * 86_400)
        calls.clear()
        aduki.scheduling.cancelOccurrence(hex, week3, notify = true)
        assertTrue(calls.contains("PATCH /v1/user/events/srv1"))
        assertTrue(held["srv1"]!!.contains("EXDATE;TZID=Africa/Nairobi:20261116T090000"))

        occurrences = aduki.scheduling.expand(from, to)
        assertEquals(3, occurrences.size)
        assertEquals(nine.toEpochMilli(), occurrences[0].start)
        assertEquals(week2.toEpochMilli(), occurrences[1].start)
        assertEquals("Standup with Guest", occurrences[1].appointment.title)
        assertEquals(nine.plusSeconds(21 * 86_400).toEpochMilli(), occurrences[2].start)

        // 3. Update following (this and future) starting from the 4th occurrence (Mon Nov 23)
        val week4 = nine.plusSeconds(21 * 86_400)
        calls.clear()
        val nextSeries = aduki.scheduling.updateFollowing(hex, week4, EventChanges(title = "Standup 2.0"), notify = true)
        assertTrue(calls.contains("PATCH /v1/user/events/srv1"))
        assertTrue(calls.contains("POST /v1/user/events"))

        occurrences = aduki.scheduling.expand(from, to)
        val week4Occurrences = occurrences.filter { it.start == week4.toEpochMilli() }
        assertEquals(1, week4Occurrences.size)
        assertEquals("Standup 2.0", week4Occurrences[0].appointment.title)
    }

    private companion object {
        const val PAGE = """{"hex":"p1","slug":"consult","name":"Consult","about":null,"lengths":[30],"before":0,"after":0,
            "notice":60,"horizon":60,"step":30,"daily":null,"weekly":null,"mode":"solo","seats":1,"visibility":"public",
            "confirm":"auto","tentative":false,"uses":null,"place":{},"questions":[],"remind":null,"paused":null,
            "created":"c","updated":"u","team":null}"""
    }
}
