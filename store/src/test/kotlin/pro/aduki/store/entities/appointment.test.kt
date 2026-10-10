package pro.aduki.store.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppointmentTest {

    @Test
    fun testAppointmentAttributes() {
        val appt = Appointment(
            hex = "apt_test",
            service = "srv_demo",
            host = "usr_alice",
            start = 1760000000000L,
            end = 1760001800000L,
            timezone = "America/New_York",
            status = "confirmed",
            uid = "uid_demo_1",
            location = "meet.google.com/abc-def-ghi",
            notes = "Intro call"
        )

        assertNotNull(appt)
        assertEquals("apt_test", appt.hex)
        assertEquals("srv_demo", appt.service)
        assertEquals("confirmed", appt.status)
        assertEquals(1760000000000L, appt.start)
    }

    @Test
    fun testServiceAttributes() {
        val srv = Service(
            hex = "srv_1",
            slug = "consult",
            name = "Consultation",
            duration = 45,
            buffer = 15,
            increment = 15,
            active = true
        )

        assertNotNull(srv)
        assertEquals("consult", srv.slug)
        assertEquals(45, srv.duration)
        assertEquals(15, srv.buffer)
    }
}

