package pro.aduki.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RecurrenceTest {

    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun times(list: List<Long>) = list.map { Instant.ofEpochMilli(it).toString() }

    @Test
    fun aSingleEventIsItselfWhenItOverlaps() {
        val s = at("2026-11-02T09:00:00Z")
        assertEquals(listOf(s), Recurrence.expand(s, s + 3_600_000, "", s + 1_000, s + 2_000))
        assertEquals(emptyList<Long>(), Recurrence.expand(s, s + 3_600_000, "", s + 3_600_000, s + 7_200_000))
    }

    @Test
    fun weeklyOnDaysWithCountAndExdate() {
        val s = at("2026-11-02T06:00:00Z") // Monday 09:00 Nairobi
        val list = Recurrence.expand(
            s, s + 3_600_000, "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6", at("2026-01-01T00:00:00Z"), at("2027-01-01T00:00:00Z"),
            "Africa/Nairobi", exdate = listOf(at("2026-11-04T06:00:00Z"))
        )
        assertEquals(
            listOf("2026-11-02T06:00:00Z", "2026-11-09T06:00:00Z", "2026-11-11T06:00:00Z", "2026-11-16T06:00:00Z", "2026-11-18T06:00:00Z"),
            times(list)
        )
    }

    @Test
    fun dailyKeepsTheWallClockAcrossDaylightSaving() {
        val s = at("2026-10-24T08:00:00Z") // 10:00 in Berlin (CEST)
        val list = Recurrence.expand(s, s, "FREQ=DAILY;INTERVAL=1", at("2026-10-24T00:00:00Z"), at("2026-10-27T00:00:00Z"), "Europe/Berlin")
        assertEquals(listOf("2026-10-24T08:00:00Z", "2026-10-25T09:00:00Z", "2026-10-26T09:00:00Z"), times(list))
    }

    @Test
    fun monthlyByOrdinalDayAndSetpos() {
        val s = at("2026-01-30T12:00:00Z") // last Friday of January
        val last = Recurrence.expand(s, s, "FREQ=MONTHLY;BYDAY=-1FR;COUNT=3", 0, Long.MAX_VALUE)
        assertEquals(listOf("2026-01-30T12:00:00Z", "2026-02-27T12:00:00Z", "2026-03-27T12:00:00Z"), times(last))
        val m = at("2026-01-30T12:00:00Z")
        val weekday = Recurrence.expand(m, m, "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=2", 0, Long.MAX_VALUE)
        assertEquals(listOf("2026-01-30T12:00:00Z", "2026-02-27T12:00:00Z"), times(weekday))
    }

    @Test
    fun monthlyOnThe31stSkipsShortMonthsAndUntilStops() {
        val s = at("2026-01-31T10:00:00Z")
        val list = Recurrence.expand(s, s, "FREQ=MONTHLY;UNTIL=20260531T235959Z", 0, Long.MAX_VALUE)
        assertEquals(listOf("2026-01-31T10:00:00Z", "2026-03-31T10:00:00Z", "2026-05-31T10:00:00Z"), times(list))
    }

    @Test
    fun yearlyAllDayOverlapsTheRange() {
        val s = at("2024-12-25T00:00:00Z")
        val list = Recurrence.expand(s, s + 86_400_000, "FREQ=YEARLY", at("2026-12-25T12:00:00Z"), at("2026-12-26T00:00:00Z"), allday = true)
        assertEquals(listOf("2026-12-25T00:00:00Z"), times(list))
    }

    @Test
    fun anOldDailyRuleStillReachesToday() {
        val s = at("2000-01-01T09:00:00Z")
        val list = Recurrence.expand(s, s + 60_000, "FREQ=DAILY", at("2026-11-02T00:00:00Z"), at("2026-11-03T00:00:00Z"))
        assertEquals(listOf("2026-11-02T09:00:00Z"), times(list))
    }
}
