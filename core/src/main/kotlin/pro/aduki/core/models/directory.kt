package pro.aduki.core.models

/**
 * A member's tenant as Aduki ID shows it in a profile (`GET /v1/me`, the
 * center's account list): its [hex], display [name], the member's
 * tenant-wide [role] (null without one) and whether the member is its [owner].
 */
data class Tenancy(
    val hex: String,
    val name: String,
    val role: String? = null,
    val owner: Boolean = false
)

/**
 * AccountProfile is one account as an account list shows it: what
 * `GET /v1/me` returns for the signed-in account, and what `GET /v1/center`
 * returns for each account of an Account Center.
 *
 * @property photo an opaque tag of the current photo (cache the image by it), or null.
 * @property center the Account Center's hex, null when the account is in none.
 * @property unlock `center` when a center unlock opens it, `self` otherwise.
 * @property links the tenant's links policy, `allow` or `deny` (`allow` for a personal account).
 * @property session the calling session (only from `GET /v1/me`).
 * @property group the calling session's device group (only from `GET /v1/me` and sign-ins).
 */
data class AccountProfile(
    val hex: String,
    val handle: String,
    val name: String = "",
    val kind: Kind = Kind.Personal,
    val tenant: Tenancy? = null,
    val photo: String? = null,
    val center: String? = null,
    val unlock: String = "self",
    val links: String = "allow",
    val session: String? = null,
    val group: String? = null
) {
    /** `personal` or `member`. */
    enum class Kind(val wire: String) {
        Personal("personal"),
        Member("member");

        companion object {
            /** The kind of a wire value (`personal`, `member`, or the token's `p`/`m`); personal when unknown. */
            fun of(value: String?): Kind = when (value) {
                "member", "m" -> Member
                else -> Personal
            }
        }
    }

    val personal: Boolean get() = kind == Kind.Personal

    /** The name to show: the display name, else the handle. */
    val label: String get() = name.ifBlank { handle }
}

/**
 * CenterView is `GET /v1/center`: the Account Center's hex ([center] is null
 * for an account in no center, which is listed alone), the center [token]
 * (for Aduki ID only), its expiry in Unix seconds, and the [accounts],
 * personal first.
 */
data class CenterView(
    val center: String?,
    val token: String,
    val expires: Long,
    val accounts: List<AccountProfile>
)

/**
 * SignedIn is a session Aduki ID just opened (a sign-in, a passkey sign-in or
 * a redeemed handoff): its [tokens], its device [group] (null from an older
 * server) and, for a sign-in with `link: true`, the single-use link [ticket]
 * that adds this account to another account's Account Center.
 */
data class SignedIn(
    val tokens: Tokens,
    val group: String? = null,
    val ticket: String? = null
)
