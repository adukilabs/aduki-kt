package pro.aduki.store.entities

/**
 * Slot represents a discrete bookable time window.
 */
data class Slot(
    val start: String,
    val end: String,
    val available: Boolean = true
)

