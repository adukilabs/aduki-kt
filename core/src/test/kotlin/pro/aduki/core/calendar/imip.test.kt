package pro.aduki.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImipTest {

    private val ical = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\n" +
        "UID:lunch-1@aduki\r\nDTSTAMP:20261104T080000Z\r\nDTSTART:20261201T100000Z\r\nDTEND:20261201T103000Z\r\n" +
        "SEQUENCE:0\r\nSUMMARY:Lunch à deux\r\nORGANIZER:mailto:ann@aduki.me\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:bob@aduki.me\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    // As Aduki Mail sends an invitation: one base64 text/calendar part.
    private fun aduki(calendar: String, method: String = "REQUEST"): String {
        val body = Base64.getEncoder().encodeToString(calendar.toByteArray()).chunked(76).joinToString("\r\n")
        return "Date: Wed, 04 Nov 2026 08:00:00 +0000\r\nFrom: <ann@aduki.me>\r\nTo: <bob@aduki.me>\r\n" +
            "Subject: Invitation: Lunch\r\nMessage-ID: <x@aduki.me>\r\nMIME-Version: 1.0\r\n" +
            "Content-Type: text/calendar; method=$method; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$body\r\n"
    }

    @Test
    fun readsTheInvitationAdukiMailSends() {
        val read = Imip.read(aduki(ical))!!
        assertEquals("REQUEST", read.method)
        assertEquals(ical, read.ical)
        val event = Events.read(read.ical)!!
        assertEquals("lunch-1@aduki", event.uid)
        assertEquals("Lunch à deux", event.title)
        assertEquals("ann@aduki.me", event.organizer)
    }

    @Test
    fun theObjectsMethodWinsOverTheHeaders() {
        val cancel = ical.replace("METHOD:REQUEST", "METHOD:CANCEL")
        assertEquals("CANCEL", Imip.read(aduki(cancel, method = "REQUEST"))!!.method)
        // Without METHOD in the object, the Content-Type parameter says it.
        assertEquals("REPLY", Imip.read(aduki(ical.replace("METHOD:REQUEST\r\n", ""), method = "REPLY"))!!.method)
    }

    @Test
    fun findsTheCalendarPartOfAMultipartMessage() {
        // As other providers send it: text and HTML, the calendar quoted-printable, an .ics attachment.
        val qp = ical.replace("=", "=3D").replace("à", "=C3=A0")
            .replace("ATTENDEE;PARTSTAT=3DNEEDS-ACTION;RSVP=3DTRUE:mailto:bob@aduki.me", "ATTENDEE;PARTSTAT=3DNEEDS-ACTION;RSVP=3DTRUE:mai=\r\nlto:bob@aduki.me")
        val raw = "From: Ann <ann@example.com>\r\nSubject: Invitation\r\nMIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed;\r\n boundary=\"outer\"\r\n\r\n" +
            "--outer\r\nContent-Type: multipart/alternative; boundary=inner\r\n\r\n" +
            "--inner\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nYou are invited.\r\n" +
            "--inner\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>You are invited.</p>\r\n" +
            "--inner\r\nContent-Type: text/calendar; charset=UTF-8; method=REQUEST\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" +
            qp + "--inner--\r\n" +
            "--outer\r\nContent-Type: application/ics; name=invite.ics\r\nContent-Disposition: attachment; filename=invite.ics\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" + Base64.getEncoder().encodeToString("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".toByteArray()) +
            "\r\n--outer--\r\n"
        val read = Imip.read(raw)
        assertNotNull(read)
        assertEquals("REQUEST", read!!.method)
        val event = Events.read(read.ical)!!
        assertEquals("Lunch à deux", event.title)
        assertTrue(event.attendees.any { it.email == "bob@aduki.me" })
    }

    @Test
    fun anIcsAttachmentAloneIsRead() {
        val raw = "From: a@example.com\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nhi\r\n" +
            "--b\r\nContent-Type: application/ics\r\n\r\n$ical--b--\r\n"
        assertEquals("lunch-1@aduki", Events.read(Imip.read(raw)!!.ical)!!.uid)
    }

    @Test
    fun aMessageWithoutACalendarIsNoInvitation() {
        assertNull(Imip.read("From: a@example.com\r\nSubject: hi\r\n\r\nNo calendar here.\r\n"))
        assertNull(Imip.read("Content-Type: text/calendar\r\n\r\nnot a calendar\r\n"))
        assertNull(Imip.read(""))
    }
}
