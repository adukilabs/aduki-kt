package pro.aduki.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pro.aduki.state.repository.Appointment as AppointmentRepo
import pro.aduki.state.repository.AppointmentSource
import pro.aduki.store.entities.Appointment as AppointmentEntity

class SchedulingTest {

    private class TestAppointmentSource : AppointmentSource {
        val list = MutableStateFlow<List<AppointmentEntity>>(emptyList())
        override fun appointments(): Flow<List<AppointmentEntity>> = list
        override fun get(hex: String): AppointmentEntity? =
            list.value.firstOrNull { it.hex == hex }
    }

    @Test
    fun testBuilder() {
        val client = Aduki.builder()
            .key("hm_test_key")
            .build()

        assertNotNull(client.scheduling)
    }

    @Test
    fun testRepository() = runBlocking {
        val source = TestAppointmentSource()
        val now = System.currentTimeMillis()
        source.list.value = listOf(
            AppointmentEntity(
                hex = "apt_1",
                service = "srv_demo",
                start = now + 3600_000,
                end = now + 7200_000,
                status = "confirmed"
            ),
            AppointmentEntity(
                hex = "apt_past",
                service = "srv_demo",
                start = now - 7200_000,
                end = now - 3600_000,
                status = "confirmed"
            )
        )

        val repo = AppointmentRepo(source = source, scope = CoroutineScope(Dispatchers.Unconfined))
        val client = Aduki.builder()
            .key("hm_test_key")
            .scheduling(repo)
            .build()

        assertNotNull(client.scheduling)
        assertEquals("apt_1", client.scheduling.get("apt_1")?.hex)
        assertNull(client.scheduling.get("nonexistent"))

        val all = client.scheduling.appointments?.value
        assertNotNull(all)
        assertEquals(2, all!!.size)

        val upcoming = client.scheduling.upcoming?.first()
        assertNotNull(upcoming)
        assertEquals(1, upcoming!!.size)
        assertEquals("apt_1", upcoming[0].hex)
    }
}

