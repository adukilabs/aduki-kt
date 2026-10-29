package pro.aduki.core.models

import java.time.Instant

/**
 * Tokens is an Aduki ID sign-in: a short-lived access token for mail, the
 * refresh token that renews it, the access lifetime in seconds, and the
 * session it belongs to (needed to sign out).
 */
data class Tokens(
    val token: String = "",
    val refresh: String = "",
    val expires: String = "",
    val session: String = ""
)

/**
 * AccountSession represents an active login/device session registered with Aduki ID.
 */
data class AccountSession(
    val id: String,
    val device: String? = null,
    val tier: Int = 2,
    val bound: Boolean = false,
    val created: Instant? = null,
    val seen: Instant? = null,
    val current: Boolean = false
) {
    /** Alias for [id] matching server field name. */
    val session: String get() = id

    /** Identifier alias. */
    val hex: String get() = id
}

/**
 * TokenStore loads, saves, and clears tokens across processes or in external
 * secure persistence (e.g. Android AccountManager).
 */
interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear() {}
}
