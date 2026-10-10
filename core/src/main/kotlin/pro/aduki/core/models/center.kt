package pro.aduki.core.models

/**
 * One account in an Account Center (ADK-AUTH-002 §7.2), as the center
 * token lists it.
 *
 * @property unlock `center` when a center unlock opens it, `self` when it
 * needs its own credential.
 */
data class Linked(
    val hex: String,
    val handle: String,
    val personal: Boolean,
    val tenant: String?,
    val unlock: String
)

/**
 * Switcher is an Account Center: its hex, the center token (sent only to
 * Aduki ID) and the accounts it holds, personal first.
 */
data class Switcher(
    val center: String,
    val token: String,
    val expires: Long,
    val accounts: List<Linked>
)

/** A session a device unlock opened for one account. */
data class Unlocked(val account: String, val tokens: Tokens)
