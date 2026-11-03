package pro.aduki.core.models

/**
 * Profile is the signed-in account as `GET /user/profile` gives it: who it
 * is, where it belongs, its plan and how much storage it uses.
 *
 * [editable] is false when the tenant locks profiles (`profile.locked`) and
 * the account is not an owner: renaming and changing the photo are refused
 * then (`AdukiException.Api` with `locked`).
 */
data class Profile(
    val hex: String,
    val name: String,
    val email: String,
    /** The photo, or null when there is none. */
    val avatar: Avatar? = null,
    val kind: Kind = Kind.PERSONAL,
    /** The tenant of a member; null for a personal account. */
    val tenant: TenantInfo? = null,
    val role: Role = Role(),
    /** The plan, or null when the account has none. */
    val plan: Plan? = null,
    val storage: Storage = Storage(),
    val editable: Boolean = true
) {
    /** Whether the account is a personal account or a member of a tenant. */
    enum class Kind { PERSONAL, MEMBER }
}

/**
 * A profile photo: [hex] is the photo's own id (its `ETag`) and changes with
 * the photo, [updated] is when it was set (epoch milliseconds). [account] is
 * the hex of the account it belongs to; it is filled by `avatars(emails)` and
 * blank in a [Profile].
 */
data class Avatar(
    val hex: String,
    val updated: Long = 0L,
    val account: String = ""
)

/** The tenant a member belongs to. */
data class TenantInfo(
    val hex: String,
    val name: String,
    val slug: String
)

/** The account's role: its [label] and whether it is a tenant [owner]. */
data class Role(
    val label: String = "",
    val owner: Boolean = false
)

/** The account's plan: its [code] (e.g. `free`) and [state] (e.g. `active`). */
data class Plan(
    val code: String,
    val state: String
)

/**
 * Storage in bytes: [used] and the [limit], null when unlimited. [pooled] is
 * true when the numbers are the Store's pooled total (drive, mail and chat),
 * false when they are the mail counter alone.
 */
data class Storage(
    val used: Long = 0L,
    val limit: Long? = null,
    val pooled: Boolean = false
)

/**
 * A photo's bytes, its `ETag` ([etag], the photo hex without quotes) and its
 * media [type] (`image/jpeg`, `image/png` or `image/webp`).
 */
class AvatarImage(
    val bytes: ByteArray,
    val etag: String,
    val type: String
) {
    override fun equals(other: Any?): Boolean =
        other is AvatarImage && etag == other.etag && type == other.type && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * etag.hashCode() + type.hashCode()) + bytes.contentHashCode()

    override fun toString(): String = "AvatarImage(etag=$etag, type=$type, bytes=${bytes.size})"
}
