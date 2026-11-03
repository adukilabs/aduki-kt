package pro.aduki.core.models

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * HolidayRegion is a region whose public holidays the account follows
 * (`GET /user/holidays`): an ISO 3166-1 alpha-2 code such as `KE`, and
 * when it was followed (RFC 3339, or blank).
 */
data class HolidayRegion(
    val region: String = "",
    val created: String = ""
)

/**
 * Holiday is one public holiday of a followed region, as the server blocks
 * it: a whole local day in the zone of the account's main schedule (UTC
 * without one). [hex] is the server's exception; [start] and [end] are the
 * day's bounds (RFC 3339); [region] is blank for a day the tenant added.
 */
data class Holiday(
    val hex: String = "",
    val name: String = "",
    val region: String = "",
    val start: String = "",
    val end: String = ""
) {
    /**
     * The calendar date of the holiday read in [zone]: the date of the middle
     * of its span there. Exact when [zone] is within 12 hours of the
     * schedule's zone (the device zone usually is). Null when the bounds do
     * not parse.
     */
    fun date(zone: ZoneId): LocalDate? = try {
        val a = Instant.parse(start)
        val b = Instant.parse(end)
        a.plusMillis((b.toEpochMilli() - a.toEpochMilli()) / 2).atZone(zone).toLocalDate()
    } catch (e: DateTimeParseException) {
        null
    }

    /** [date] in UTC: right for every schedule zone from UTC-11 to UTC+12. */
    val date: LocalDate? get() = date(ZoneOffset.UTC)
}
