package pro.aduki.core.models

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
 * TokenStore loads, saves, and clears tokens across processes or in external
 * secure persistence (e.g. Android AccountManager).
 */
interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear() {}
}
