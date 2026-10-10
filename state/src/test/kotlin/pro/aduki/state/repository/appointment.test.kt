package pro.aduki.state.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import pro.aduki.store.entities.Appointment as AppointmentEntity

class AppointmentTest {

    private class TestAppointmentSource : AppointmentSource {
        val flow = MutableStateFlow<List<AppointmentEntity>>(emptyList())

        override fun appointments(): Flow<List<AppointmentEntity>> = flow
        override fun get(hex: String): AppointmentEntity? = flow.value.firstOrNull { it.hex == hex }
    }

    @Test
    fun testAppointmentChronologicalOrdering() = runBlocking {
        val source = TestAppointmentSource()
        val repo = Appointment(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        val now = System.currentTimeMillis()
        source.flow.value = listOf(
            AppointmentEntity(hex = "a3", start = now + 100000, status = "confirmed"),
            AppointmentEntity(hex = "a1", start = now - 50000, status = "confirmed"),
            AppointmentEntity(hex = "a2", start = now + 20000, status = "confirmed")
        )

        val all = repo.all.value
        assertEquals(3, all.size)
        assertEquals("a1", all[0].hex)
        assertEquals("a2", all[1].hex)
        assertEquals("a3", all[2].hex)

        val upcoming = repo.upcoming.first()
        assertEquals(2, upcoming.size)
        assertEquals("a2", upcoming[0].hex)
        assertEquals("a3", upcoming[1].hex)

        val single = repo.get("a2")
        assertNotNull(single)
        assertEquals("a2", single!!.hex)
    }
}

