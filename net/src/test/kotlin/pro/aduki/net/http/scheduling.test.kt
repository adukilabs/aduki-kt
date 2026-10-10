package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SchedulingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testAppointmentsList() {
        val json = """
            [
                {
                    "hex": "apt_123",
                    "service": "srv_abc",
                    "host": "usr_host1",
                    "start": "2026-10-15T14:00:00Z",
                    "end": "2026-10-15T14:30:00Z",
                    "timezone": "UTC",
                    "status": "confirmed",
                    "uid": "uid_123",
                    "location": "google_meet",
                    "notes": "Consultation"
                }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val client = OkHttpClient()
        val scheduling = Scheduling(client, server.url("/").toString())
        val list = scheduling.appointments(activeOnly = true)

        assertEquals(1, list.size)
        assertEquals("apt_123", list[0].hex)
        assertEquals("srv_abc", list[0].service)
        assertEquals("confirmed", list[0].status)
    }

    @Test
    fun testCreateAppointment() {
        val responseJson = """
            {
                "hex": "apt_new",
                "service": "srv_consult",
                "host": "usr_host",
                "start": "2026-10-15T15:00:00Z",
                "end": "2026-10-15T15:30:00Z",
                "status": "confirmed"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val client = OkHttpClient()
        val scheduling = Scheduling(client, server.url("/").toString())
        val appt = scheduling.create(
            service = "srv_consult",
            start = "2026-10-15T15:00:00Z",
            end = "2026-10-15T15:30:00Z",
            notes = "Test"
        )

        assertEquals("apt_new", appt.hex)
        assertEquals("srv_consult", appt.service)
    }

    @Test
    fun testAvailabilitySlots() {
        val json = """
            {
                "slots": [
                    { "start": "2026-10-15T09:00:00Z", "end": "2026-10-15T09:30:00Z" },
                    { "start": "2026-10-15T09:30:00Z", "end": "2026-10-15T10:00:00Z" }
                ],
                "busy": []
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val client = OkHttpClient()
        val scheduling = Scheduling(client, server.url("/").toString())
        val slots = scheduling.availability("2026-10-15T09:00:00Z", "2026-10-15T17:00:00Z")

        assertEquals(2, slots.size)
        assertEquals("2026-10-15T09:00:00Z", slots[0].start)
    }

    @Test
    fun testCancelAppointment() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"cancelled"}"""))

        val client = OkHttpClient()
        val scheduling = Scheduling(client, server.url("/").toString())
        val result = scheduling.cancel("apt_123", "client request")

        assertTrue(result)
    }
}

