package pro.aduki.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.core.models.EventChanges
import pro.aduki.core.models.EventRequest
import pro.aduki.core.models.Response
import pro.aduki.core.models.ServiceRequest
import pro.aduki.core.models.Window
import java.time.Instant

class EventsTest {

    private val nine = Instant.parse("2026-11-02T06:00:00Z") // 09:00 in Nairobi

    // As stored by the server (live reply, account hex replaced).
    private val stored = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:fixture-1@aduki\r\n" +
        "DTSTAMP:20261024T100000Z\r\nSEQUENCE:0\r\nDTSTART;TZID=Africa/Nairobi:20261102T090000\r\n" +
        "DTEND;TZID=Africa/Nairobi:20261102T100000\r\nSUMMARY:Standup\\, daily\r\nLOCATION:HQ\r\nX-CUSTOM;A=1:keep me\r\n" +
        "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6\r\nATTENDEE;CN=\"Bob; B\";PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:Bob@Example.com\r\n" +
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n" +
        "END:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test
    fun readsTheMasterEvent() {
        val e = Events.read(stored)!!
        assertEquals("fixture-1@aduki", e.uid)
        assertEquals("Standup, daily", e.title)
        assertEquals(nine.toEpochMilli(), e.start)
        assertEquals(nine.plusSeconds(3600).toEpochMilli(), e.end)
        assertEquals("Africa/Nairobi", e.timezone)
        assertEquals(listOf(Attendee("bob@example.com", "NEEDS-ACTION", "Bob; B")), e.attendees)
        assertEquals(listOf(15), e.reminders)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6", e.rrule)
        assertFalse(e.allday)
    }

    @Test
    fun buildsAnAllDayRepeatingEventWithRemindersAndAttendees() {
        val ical = Events.build(
            EventRequest(
                title = "Holiday", start = Instant.parse("2026-11-05T00:00:00Z"), end = Instant.parse("2026-11-05T00:00:00Z"),
                allday = true, attendees = listOf("a@example.com"), rrule = "FREQ=YEARLY", reminders = listOf(0, 1440),
                notes = "line one\nline, two"
            ),
            uid = "h1"
        )
        assertTrue(ical.contains("DTSTART;VALUE=DATE:20261105\r\n"))
        assertTrue(ical.contains("DTEND;VALUE=DATE:20261106\r\n"))
        assertTrue(ical.contains("DESCRIPTION:line one\\nline\\, two\r\n"))
        assertTrue(ical.contains("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:a@example.com\r\n"))
        val e = Events.read(ical)!!
        assertTrue(e.allday)
        assertEquals(Instant.parse("2026-11-06T00:00:00Z").toEpochMilli(), e.end)
        assertEquals(listOf(0, 1440), e.reminders)
        assertEquals("line one\nline, two", e.notes)
    }

    @Test
    fun changeKeepsWhatItDoesNotTouchAndBumpsTheSequence() {
        val out = Events.change(stored, EventChanges(start = nine.plusSeconds(1800), location = ""))
        val e = Events.read(out)!!
        assertEquals(1, e.sequence)
        assertEquals(nine.plusSeconds(1800).toEpochMilli(), e.start)
        assertEquals(nine.plusSeconds(5400).toEpochMilli(), e.end) // length kept
        assertEquals("", e.location)
        assertTrue(out.contains("DTSTART;TZID=Africa/Nairobi:20261102T093000\r\n"))
        assertTrue(out.contains("X-CUSTOM;A=1:keep me\r\n"))
        assertEquals(listOf(15), e.reminders)
        assertEquals("bob@example.com", e.attendees.single().email)

        val more = Events.change(out, EventChanges(attendees = listOf("bob@example.com", "cy@example.com"), reminders = emptyList()))
        val m = Events.read(more)!!
        assertEquals(listOf("bob@example.com", "cy@example.com"), m.attendees.map { it.email })
        assertEquals("Bob; B", m.attendees[0].name) // the kept line
        assertEquals(emptyList<Int>(), m.reminders)
        assertEquals(2, m.sequence)
    }

    @Test
    fun respondSetsOnlyTheAnswer() {
        val out = Events.respond(stored, "bob@example.com", Response.Tentative)!!
        val e = Events.read(out)!!
        assertEquals("TENTATIVE", e.attendees.single().status)
        assertEquals(0, e.sequence)
        assertTrue(out.contains("ATTENDEE;CN=\"Bob; B\";PARTSTAT=TENTATIVE;RSVP=TRUE:mailto:Bob@Example.com\r\n"))
        assertNull(Events.respond(stored, "eve@example.com", Response.Accepted))
    }

    @Test
    fun cancelMarksAndComments() {
        val e = Events.read(Events.cancel(stored, "ill"))!!
        assertEquals("CANCELLED", e.status)
        assertEquals(1, e.sequence)
    }

    @Test
    fun splitAndJoinRoundTrip() {
        val file = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//EN\r\n" +
            "BEGIN:VTIMEZONE\r\nTZID:Africa/Nairobi\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0300\r\nTZOFFSETTO:+0300\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n" +
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART;TZID=Africa/Nairobi:20261102T090000\r\nSUMMARY:A\r\nRRULE:FREQ=DAILY\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:a\r\nRECURRENCE-ID;TZID=Africa/Nairobi:20261103T090000\r\nDTSTART;TZID=Africa/Nairobi:20261103T100000\r\nSUMMARY:A moved\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:b\r\nDTSTART:20261104T090000Z\r\nSUMMARY:B\r\nEND:VEVENT\r\n" +
            "BEGIN:VTODO\r\nUID:t\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val parts = Events.split(file)
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("TZID:Africa/Nairobi"))
        assertEquals("A", Events.read(parts[0])!!.title) // the master, not the override
        assertFalse(parts[1].contains("VTIMEZONE"))
        val joined = Events.join(parts)
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(joined).count())
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(joined).count())
    }

    @Test
    fun longLinesFoldAndUnfold() {
        val title = "é".repeat(60) + " 😀 end"
        val ical = Events.build(EventRequest(title = title, start = nine, end = nine.plusSeconds(60)), "u")
        assertTrue(ical.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(title, Events.read(ical)!!.title)
    }

    @Test
    fun updateOccurrenceWritesRecurrenceIdOverride() {
        val wednesday = nine.plusSeconds(2 * 86_400) // 2026-11-04T06:00:00Z -> 09:00 Nairobi
        val out = Events.updateOccurrence(stored, wednesday, EventChanges(title = "Standup - Special", location = "Room 42"))
        assertTrue(out.contains("RECURRENCE-ID;TZID=Africa/Nairobi:20261104T090000\r\n"))
        assertTrue(out.contains("SUMMARY:Standup - Special\r\n"))
        assertTrue(out.contains("LOCATION:Room 42\r\n"))
        // Master event still intact
        val master = Events.read(out)!!
        assertEquals("Standup, daily", master.title)
        assertEquals("HQ", master.location)

        val overrides = Events.overrides(out)
        assertEquals(1, overrides.size)
        assertEquals(wednesday.toEpochMilli(), overrides[0].recurrenceId)
        assertEquals("Standup - Special", overrides[0].event.title)
        assertEquals("Room 42", overrides[0].event.location)
    }

    @Test
    fun cancelOccurrenceAddsExdate() {
        val wednesday = nine.plusSeconds(2 * 86_400)
        val out = Events.cancelOccurrence(stored, wednesday)
        assertTrue(out.contains("EXDATE;TZID=Africa/Nairobi:20261104T090000\r\n"))
        val master = Events.read(out)!!
        assertTrue(master.exdate.contains(wednesday.toEpochMilli()))
    }

    @Test
    fun updateFollowingSplitsWithUntil() {
        val nextWeek = nine.plusSeconds(7 * 86_400) // 2026-11-09T06:00:00Z
        val (originalIcal, newIcal) = Events.updateFollowing(stored, nextWeek, EventChanges(title = "Standup 2.0"))
        // Original has UNTIL
        assertTrue(originalIcal.contains("UNTIL="))
        val original = Events.read(originalIcal)!!
        assertEquals("Standup, daily", original.title)
        assertTrue(original.rrule.contains("UNTIL="))

        // New series starts at nextWeek with new title
        val newEvent = Events.read(newIcal)!!
        assertEquals("Standup 2.0", newEvent.title)
        assertEquals(nextWeek.toEpochMilli(), newEvent.start)
        assertFalse(newEvent.rrule.contains("UNTIL="))
    }

    @Test
    fun requestsValidate() {
        assertThrows(IllegalArgumentException::class.java) { EventRequest("t", nine, nine).validate() }
        assertThrows(IllegalArgumentException::class.java) { EventRequest("t", nine, nine.plusSeconds(1), rrule = "BYDAY=MO").validate() }
        assertThrows(IllegalArgumentException::class.java) { EventRequest("t", nine, nine.plusSeconds(1), timezone = "Mars/Base").validate() }
        EventRequest("t", nine, nine, allday = true).validate()
        assertThrows(IllegalArgumentException::class.java) { ServiceRequest(slug = "Bad Slug").validate() }
        assertThrows(IllegalArgumentException::class.java) { ServiceRequest(windows = listOf(Window(listOf("mon"), "9:00", "17:00"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { ServiceRequest(reminders = listOf(1)).validate() }
        ServiceRequest(name = "n", slug = "a-1", lengths = listOf(30), windows = listOf(Window(listOf("Mon"), "09:00", "17:00"))).validate()
    }
}
