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

    @Test
    fun observeExpandsRepeatsInTheRangeAndFollowsChanges() = runBlocking {
        val source = TestAppointmentSource()
        val repo = Appointment(source = source, scope = CoroutineScope(Dispatchers.Unconfined))
        val day = 86_400_000L
        val monday = 1_793_005_200_000L // 2026-10-26T09:00:00Z, a Monday
        source.flow.value = listOf(
            AppointmentEntity(hex = "w", start = monday, end = monday + 3_600_000, rrule = "FREQ=WEEKLY;BYDAY=MO,WE",
                exdate = (monday + 2 * day).toString()),
            AppointmentEntity(hex = "x", start = monday + day, end = monday + day + 60_000, status = "cancelled")
        )

        val week = repo.observe(monday, monday + 14 * day)
        // Mon 26, (Wed 28 excluded), Mon 2, Wed 4; the cancelled one is left out.
        assertEquals(listOf(monday, monday + 7 * day, monday + 9 * day), week.value.map { it.start })
        assertEquals(monday + 7 * day + 3_600_000, week.value[1].end)

        source.flow.value = source.flow.value.drop(1)
        assertEquals(emptyList<Occurrence>(), week.value)
    }
}
