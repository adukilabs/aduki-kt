package pro.aduki.core.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Recurrence expands an RRULE (RFC 5545 §3.3.10) into occurrences: FREQ
 * (DAILY, WEEKLY, MONTHLY, YEARLY; HOURLY and MINUTELY without BY parts),
 * INTERVAL, COUNT, UNTIL, BYDAY (with ordinals in monthly and yearly
 * rules), BYMONTHDAY, BYMONTH and BYSETPOS, less EXDATEs. Occurrences
 * keep the wall-clock time of the first one in its zone, so they follow
 * daylight saving time.
 */
object Recurrence {
    /** The most occurrences one call walks through, a guard against unbounded rules. */
    const val MOST = 100_000

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY
    )

    private data class Day(val ordinal: Int, val day: DayOfWeek)

    /**
     * The starts (epoch millis) of the occurrences of an event that overlap
     * `[from, to)`. [start] and [end] are the first occurrence; without a
     * [rule] that is the only one. All-day events expand in UTC.
     */
    fun expand(
        start: Long,
        end: Long,
        rule: String,
        from: Long,
        to: Long,
        zone: String = "UTC",
        allday: Boolean = false,
        exdate: List<Long> = emptyList()
    ): List<Long> {
        val length = maxOf(0L, end - start)
        fun overlaps(s: Long) = if (length == 0L) s in from until to else s < to && s + length > from
        if (rule.isBlank()) return if (overlaps(start)) listOf(start) else emptyList()

        val parts = rule.split(';').filter { '=' in it }
            .associate { it.substringBefore('=').trim().uppercase() to it.substringAfter('=').trim() }
        val freq = parts["FREQ"]?.uppercase() ?: return if (overlaps(start)) listOf(start) else emptyList()
        val interval = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1L
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]?.let(::until)
        val byday = parts["BYDAY"]?.split(',')?.mapNotNull(::day).orEmpty()
        val bymonthday = parts["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val bymonth = parts["BYMONTH"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val bysetpos = parts["BYSETPOS"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

        val tz = if (allday) ZoneOffset.UTC else runCatching { ZoneId.of(zone) }.getOrDefault(ZoneOffset.UTC)
        val first = Instant.ofEpochMilli(start).atZone(tz)
        val clock = first.toLocalTime()
        val skipped = exdate.toSet()
        val out = mutableListOf<Long>()
        var seen = 0
        var period = 0L

        while (seen < MOST) {
            val dates: List<LocalDateTime> = when (freq) {
                "HOURLY" -> listOf(first.toLocalDateTime().plusHours(period * interval))
                "MINUTELY" -> listOf(first.toLocalDateTime().plusMinutes(period * interval))
                "SECONDLY" -> listOf(first.toLocalDateTime().plusSeconds(period * interval))
                else -> candidates(freq, first.toLocalDate(), period * interval, byday, bymonthday, bymonth)
                    .let { if (bysetpos.isEmpty()) it else setpos(it, bysetpos) }
                    .map { it.atTime(clock) }
            }
            for (local in dates.sorted()) {
                val at = ZonedDateTime.ofLocal(local, tz, null).toInstant().toEpochMilli()
                if (at < start) continue
                if (until != null && at > until) return out
                if (count != null && seen >= count) return out
                if (at >= to) return out
                seen++
                if (at !in skipped && overlaps(at)) out.add(at)
            }
            period++
            // A rule whose BY parts never match (e.g. BYMONTHDAY=31 with BYMONTH=2) ends here.
            if (period > 200_000) break
        }
        return out
    }

    private fun candidates(
        freq: String,
        first: LocalDate,
        step: Long,
        byday: List<Day>,
        bymonthday: List<Int>,
        bymonth: List<Int>
    ): List<LocalDate> {
        val list: List<LocalDate> = when (freq) {
            "DAILY" -> listOf(first.plusDays(step)).filter { d ->
                (byday.isEmpty() || byday.any { it.day == d.dayOfWeek }) &&
                    (bymonthday.isEmpty() || monthday(d, bymonthday))
            }
            "WEEKLY" -> {
                val monday = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(step)
                if (byday.isEmpty()) listOf(monday.plusDays((first.dayOfWeek.value - 1).toLong()))
                else byday.map { monday.plusDays((it.day.value - 1).toLong()) }
            }
            "MONTHLY" -> month(first.withDayOfMonth(1).plusMonths(step), first, byday, bymonthday)
            "YEARLY" -> {
                val year = first.withDayOfYear(1).plusYears(step)
                val inMonths = if (bymonth.isEmpty()) listOf(first.monthValue) else bymonth
                inMonths.filter { it in 1..12 }.flatMap { m -> month(year.withMonth(m), first, byday, bymonthday) }
            }
            else -> emptyList()
        }
        return list.filter { bymonth.isEmpty() || it.monthValue in bymonth }.distinct()
    }

    // The days of one month a monthly (or yearly, per month) rule picks.
    private fun month(month: LocalDate, first: LocalDate, byday: List<Day>, bymonthday: List<Int>): List<LocalDate> {
        val size = month.lengthOfMonth()
        return when {
            bymonthday.isNotEmpty() -> bymonthday.mapNotNull { d ->
                val day = if (d < 0) size + d + 1 else d
                if (day in 1..size) month.withDayOfMonth(day) else null
            }.filter { d -> byday.isEmpty() || byday.any { it.day == d.dayOfWeek } }
            byday.isNotEmpty() -> byday.flatMap { b ->
                val all = (1..size).map { month.withDayOfMonth(it) }.filter { it.dayOfWeek == b.day }
                when {
                    b.ordinal == 0 -> all
                    b.ordinal > 0 -> listOfNotNull(all.getOrNull(b.ordinal - 1))
                    else -> listOfNotNull(all.getOrNull(all.size + b.ordinal))
                }
            }
            first.dayOfMonth <= size -> listOf(month.withDayOfMonth(first.dayOfMonth))
            else -> emptyList()
        }
    }

    private fun monthday(d: LocalDate, list: List<Int>): Boolean {
        val size = d.lengthOfMonth()
        return list.any { (if (it < 0) size + it + 1 else it) == d.dayOfMonth }
    }

    private fun setpos(list: List<LocalDate>, positions: List<Int>): List<LocalDate> {
        val sorted = list.sorted()
        return positions.mapNotNull { p -> if (p > 0) sorted.getOrNull(p - 1) else sorted.getOrNull(sorted.size + p) }
    }

    private fun day(text: String): Day? {
        val t = text.trim().uppercase()
        val name = t.takeLast(2)
        val day = DAYS[name] ?: return null
        val ordinal = t.dropLast(2).ifBlank { "0" }.toIntOrNull() ?: return null
        return Day(ordinal, day)
    }

    private fun until(text: String): Long? = try {
        val t = text.trim()
        if (t.length == 8) {
            LocalDate.parse(t, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli() - 1
        } else {
            LocalDateTime.parse(t.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    } catch (_: Exception) {
        null
    }
}
