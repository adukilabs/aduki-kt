package pro.aduki.core.models

/**
 * Slot models a discrete bookable time window.
 */
data class Slot(
    val start: String = "",
    val end: String = "",
    val available: Boolean = true
)

/**
 * AppointmentRecord models a meeting reservation.
 */
data class AppointmentRecord(
    val hex: String = "",
    val service: String = "",
    val host: String = "",
    val start: String = "",
    val end: String = "",
    val timezone: String = "UTC",
    val status: String = "confirmed",
    val uid: String = "",
    val location: String = "",
    val notes: String = ""
)

/**
 * ServiceRecord models a meeting template or event type.
 */
data class ServiceRecord(
    val hex: String = "",
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val duration: Int = 30,
    val buffer: Int = 0,
    val notice: Int = 60,
    val horizon: Int = 30,
    val increment: Int = 15,
    val active: Boolean = true
)

