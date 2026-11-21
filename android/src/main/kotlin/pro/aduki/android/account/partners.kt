package pro.aduki.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * First-class partner apps: apps by another publisher (another signing key)
 * that may see **who is signed in** to Aduki on this device, never a token.
 *
 * The Aduki apps grant these packages visibility of every `pro.aduki.account`
 * entry ([Accounts.share], [AccountDirectory.upsert]), and the authenticator
 * answers their [PartnerAccounts.PROFILE] requests with an entry's public
 * profile. Nothing else is answered for them: an access-token request from a
 * partner is refused, and the entry's user data, password and cached auth
 * tokens stay readable only by apps signed like the authenticator (Android
 * enforces that for every caller of another signature).
 *
 * The host app sets the list before it signs anyone in (in `Application.onCreate`);
 * an empty list turns partner sharing off.
 */
object Partners {
    /** The partners shipped by default: LavernAI (`com.lavernai.triviaconnect`). */
    val DEFAULT: List<String> = listOf("com.lavernai.triviaconnect")

    /** The partner packages of this app. */
    @Volatile
    var packages: List<String> = DEFAULT

    /** [extra] packages plus the [packages] of the partners, once each. */
    fun with(extra: List<String>): List<String> = (extra + packages).filter { it.isNotBlank() }.distinct()
}

/**
 * An Aduki account signed in on this device, as a partner app sees it:
 * public profile data only. [hex] and the other fields are null when the
 * authenticator could not be asked (no Aduki app installed that answers
 * partners, or the call timed out); [handle] is always there (it is the
 * entry's name).
 */
data class PartnerAccount(
    /** The account's hex (send it as `login_hint` to sign in with this account). */
    val hex: String?,
    /** The account's handle, e.g. `ada@aduki.me`. */
    val handle: String,
    /** The display name, or empty. */
    val name: String = "",
    /** True for a personal account, false for a member of an organisation. */
    val personal: Boolean = true,
    /** The organisation's name of a member account, or null. */
    val tenant: String? = null,
    /** An opaque tag of the current photo (the image itself needs a session), or null. */
    val photo: String? = null
) {
    /** The name to show: the display name, else the handle. */
    val label: String get() = name.ifBlank { handle }
}

/**
 * The read API for partner apps (apps that do **not** ship the Aduki
 * authenticator): the Aduki accounts on this device, for a "Continue as …"
 * list on a sign-in screen.
 *
 * ```kotlin
 * val accounts = withContext(Dispatchers.IO) { PartnerAccounts.list(context) }
 * ```
 *
 * It sees only the accounts the Aduki apps made visible to the calling
 * package (see [Partners]) and returns their public profile. It never
 * returns a token: sign in with the account through the app's own OAuth
 * flow, with the [PartnerAccount.hex] as `login_hint`.
 *
 * [list] blocks (it asks the authenticator over binder): call it off the main thread.
 */
object PartnerAccounts {
    /** The auth token type a partner asks for: a JSON profile, never a token. */
    const val PROFILE = "pro.aduki.profile"

    /** The result key of the profile JSON (also put in `KEY_AUTHTOKEN`). */
    const val KEY_PROFILE = "pro.aduki.profile"

    /**
     * The Aduki accounts on this device visible to this app, personal first
     * then by handle. Empty when none is visible or no Aduki app is installed.
     * Each authenticator answer waits at most [timeout] milliseconds.
     */
    fun list(context: Context, timeout: Long = 3_000): List<PartnerAccount> =
        list(Source.of(AccountManager.get(context.applicationContext), timeout))

    internal fun list(source: Source): List<PartnerAccount> =
        source.accounts().map { account -> read(source, account) }
            .sortedWith(compareByDescending<PartnerAccount> { it.personal }.thenBy { it.handle.lowercase() })

    // An app signed like the authenticator reads the entry; any other app gets a SecurityException and asks the authenticator.
    private fun read(source: Source, account: Account): PartnerAccount {
        val direct = runCatching { source.data(account, "hex") }.getOrNull()
        if (direct != null) {
            fun get(key: String) = runCatching { source.data(account, key) }.getOrNull()?.ifBlank { null }
            return PartnerAccount(
                hex = direct,
                handle = get("handle") ?: account.name,
                name = get("name").orEmpty(),
                personal = get("personal") != "false",
                tenant = get("tenantName"),
                photo = get("photo")
            )
        }
        val json = runCatching { source.profile(account) }.getOrNull()
        return parse(json, account.name) ?: PartnerAccount(hex = null, handle = account.name)
    }

    /** The JSON the authenticator returns to a partner: the allow-listed public fields of [account], nothing else. */
    internal fun profile(account: DeviceAccount): String = JSONObject().apply {
        val p = account.profile
        put("hex", p.hex)
        put("handle", p.handle)
        put("name", p.name)
        put("personal", p.personal)
        p.tenant?.name?.takeIf { it.isNotBlank() }?.let { put("tenant", it) }
        p.photo?.let { put("photo", it) }
    }.toString()

    internal fun parse(json: String?, fallback: String): PartnerAccount? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        fun get(key: String) = o.optString(key).ifBlank { null }
        return PartnerAccount(
            hex = get("hex") ?: return null,
            handle = get("handle") ?: fallback,
            name = get("name").orEmpty(),
            personal = o.optBoolean("personal", true),
            tenant = get("tenant"),
            photo = get("photo")
        )
    }

    /** Where [list] reads from (tests replace it). */
    internal interface Source {
        fun accounts(): List<Account>

        /** The entry's user data; throws SecurityException for an app not signed like the authenticator. */
        fun data(account: Account, key: String): String?

        /** The authenticator's [PROFILE] answer, or null. */
        fun profile(account: Account): String?

        companion object {
            fun of(manager: AccountManager, timeout: Long): Source = object : Source {
                override fun accounts(): List<Account> = manager.getAccountsByType(Accounts.TYPE).toList()
                override fun data(account: Account, key: String): String? = manager.getUserData(account, key)
                override fun profile(account: Account): String? {
                    val result = manager.getAuthToken(account, PROFILE, Bundle(), false, null, null)
                        .getResult(timeout, TimeUnit.MILLISECONDS)
                    return result.getString(KEY_PROFILE) ?: result.getString(AccountManager.KEY_AUTHTOKEN)
                }
            }
        }
    }
}

/** Who called the authenticator. */
internal enum class Caller {
    /** This app, or an app signed with the same key (a sibling). */
    Sibling,

    /** A package in [Partners.packages] of another signature: public profile only. */
    Partner,

    /** Anyone else: nothing. */
    Other;

    companion object {
        /** Classifies the calling [uid] with the package manager of [context]. */
        fun of(context: Context, uid: Int): Caller {
            if (uid == Process.myUid()) return Sibling
            val pm = context.packageManager
            if (pm.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH) return Sibling
            val packages = pm.getPackagesForUid(uid).orEmpty()
            return if (packages.any { it in Partners.packages }) Partner else Other
        }
    }
}
