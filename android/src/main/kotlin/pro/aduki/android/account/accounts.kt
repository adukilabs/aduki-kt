package pro.aduki.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import okhttp3.OkHttpClient
import org.json.JSONObject
import pro.aduki.core.models.Linked
import pro.aduki.core.models.Switcher
import pro.aduki.core.models.Tokens
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.dpop.Key
import pro.aduki.android.keystore.AndroidKey
import pro.aduki.net.http.Center

/**
 * Provider for AccountManager cross-app visibility on API 26+.
 */
interface Visibility {
    fun set(account: Account, target: String, mode: Int): Boolean
    fun get(account: Account, target: String): Int

    companion object {
        fun system(manager: AccountManager): Visibility = object : Visibility {
            private val fallback = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Int>()

            override fun set(account: Account, target: String, mode: Int): Boolean {
                fallback[account.name to target] = mode
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        manager.setAccountVisibility(account, target, mode)
                    } catch (_: Exception) {
                        // In unit tests or sandboxed environments, AccountManager.mService may be unbound
                    }
                }
                return true
            }

            override fun get(account: Account, target: String): Int {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        val v = manager.getAccountVisibility(account, target)
                        if (v != 0) return v
                    } catch (_: Exception) {
                    }
                    return fallback[account.name to target] ?: AccountManager.VISIBILITY_NOT_VISIBLE
                }
                return AccountManager.VISIBILITY_VISIBLE
            }
        }
    }
}

/**
 * Accounts keeps an Account Center's linked accounts in Android's
 * [AccountManager] (K4, ADK-AUTH-002 section 5.5): one entry per linked
 * account under the one [TYPE], the center id in each entry's user data so the
 * authenticator knows which entries belong together.
 *
 * Tokens are sealed with the [Vault] (Keystore-backed on a device) before they
 * reach AccountManager, which stores them in the system account database. The
 * center token (ADK-AUTH-002 section 7.2) is sent to Aduki ID only and is
 * kept in its own private file, never in an account entry and never next to an
 * access token: access tokens carry no center data, and neither does what this
 * class hands to app services.
 *
 * ## Cross-App Account Sharing
 * On Android 8+ (API 26+), accounts added by one application are isolated and
 * invisible to other applications by default. Call [share] with target package names
 * to grant visibility via [AccountManager.setAccountVisibility].
 *
 * ### Safe vs. App-Bound Fields
 * - **Safe across apps:** `hex`, `handle`, `center`, `tenant`, `personal`, `unlock`.
 *   These metadata fields are unsealed [AccountManager] user data readable by any
 *   companion app granted visibility.
 * - **App-bound (with Vault):** Access and refresh tokens sealed with [Vault.platform()]
 *   use an Android Keystore key belonging exclusively to the creating application's UID.
 *
 * ### Token Sharing Patterns
 * 1. **Per-App Sessions (Recommended):** Peer apps read `handle`, `hex`, and `center`
 *    metadata from [Accounts] and establish their own DPoP session with their own
 *    app-specific private key.
 * 2. **Unsealed Tokens (`vault = null`):** Using `Accounts(context, vault = null)` stores
 *    tokens unsealed in [AccountManager], allowing companion apps with visibility
 *    to read and share session tokens directly.
 */
class Accounts(
    context: Context,
    private val vault: Vault? = Vault.platform(),
    private val manager: AccountManager = AccountManager.get(context),
    private val center: CenterStore = CenterStore(context, vault),
    private val visibility: Visibility = Visibility.system(manager)
) {
    private val packageName: String = context.packageName
    private val tokensPrefs = context.applicationContext.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE)

    /** One entry, as read back. */
    data class Entry(
        val account: Account,
        val hex: String,
        val handle: String,
        val personal: Boolean,
        val tenant: String?,
        /** `center` or `self` (ADK-AUTH-002 section 5.4). */
        val unlock: String,
        val center: String
    )

    /** What a [reconcile] changed. */
    data class Change(val added: List<String>, val removed: List<String>, val updated: List<String>)

    /** All entries of this type, personal first then by handle. */
    fun list(): List<Entry> = manager.getAccountsByType(TYPE).mapNotNull(::read)
        .sortedWith(compareByDescending<Entry> { it.personal }.thenBy { it.handle })

    fun find(hex: String): Entry? = list().firstOrNull { it.hex == hex }

    /**
     * Makes the entries of [switcher]'s center match it: accounts the center
     * lists are added or refreshed, entries of that center it no longer lists
     * are removed with their tokens (a tenant `links = deny`, a forced unlink or
     * the person's own unlink, ADK-AUTH-002 section 5.3). Entries of other
     * centers and tokens of kept entries are left alone. The center token is
     * stored for later calls to Aduki ID.
     */
    @Synchronized
    fun reconcile(switcher: Switcher): Change {
        val wanted = switcher.accounts.associateBy { it.hex }
        val existing = list()
        val removed = existing.filter { it.center == switcher.center && it.hex !in wanted }
        removed.forEach(::drop)
        val added = ArrayList<String>()
        val updated = ArrayList<String>()
        for (linked in switcher.accounts) {
            val have = existing.firstOrNull { it.hex == linked.hex }
            if (have == null) {
                if (create(linked, switcher.center)) added += linked.hex
            } else if (have.center != switcher.center || have.unlock != linked.unlock || have.tenant != linked.tenant || have.handle != linked.handle) {
                if (have.handle != linked.handle) drop(have).also { create(linked, switcher.center) } else {
                    write(have.account, linked, switcher.center)
                }
                updated += linked.hex
            }
        }
        center.save(switcher.center, switcher.token, switcher.expires)
        return Change(added, removed.map { it.hex }, updated)
    }

    /**
     * Fetches the center of the account [current] (hex; [bearer] is its access
     * token for audience `id`) and reconciles. When Aduki ID says it is in no
     * center, every other entry that belonged to a center is removed and
     * [current] stays as a single account.
     */
    fun refresh(client: OkHttpClient, identity: String, bearer: String, current: String): Change {
        val switcher = Center.fetch(client, identity, bearer)
        if (switcher != null) return reconcile(switcher)
        val gone = list().filter { it.hex != current && it.center.isNotEmpty() }
        gone.forEach(::drop)
        find(current)?.let { manager.setUserData(it.account, CENTER, "") }
        center.clearCenter()
        return Change(emptyList(), gone.map { it.hex }, emptyList())
    }

    /** Adds (or updates) a single account that is in no center, e.g. right after sign-in. */
    @Synchronized
    fun add(linked: Linked, tokens: Tokens? = null): Boolean {
        val have = find(linked.hex)
        val ok = have != null || create(linked, "")
        if (ok && tokens != null) save(linked.hex, tokens)
        return ok
    }

    /** Stores the session [tokens] of the account [hex]; false when no such entry exists. */
    fun save(hex: String, tokens: Tokens, now: Long = System.currentTimeMillis()): Boolean {
        val entry = find(hex) ?: return false
        val json = JSONObject().apply {
            put("token", seal(tokens.token))
            put("refresh", seal(tokens.refresh))
            put("expires", tokens.expires)
            put("session", tokens.session)
            put("saved", now)
        }
        tokensPrefs.edit().putString(hex, json.toString()).apply()

        manager.setUserData(entry.account, SAVED, now.toString())
        manager.setUserData(entry.account, EXPIRES, tokens.expires)
        manager.setUserData(entry.account, SESSION, tokens.session)
        manager.setPassword(entry.account, seal(tokens.refresh))
        manager.setAuthToken(entry.account, ACCESS, seal(tokens.token))
        return true
    }

    /** The stored session of [hex] (access, refresh, expiry, session id), or null when none was saved. */
    fun tokens(hex: String): Tokens? {
        val entry = find(hex) ?: return null
        val stored = tokensPrefs.getString(hex, null)
        if (stored != null) {
            val parsed = runCatching {
                val json = JSONObject(stored)
                val access = open(json.optString("token"))
                val refresh = open(json.optString("refresh"))
                if (access.isNotEmpty() || refresh.isNotEmpty()) {
                    Tokens(
                        token = access,
                        refresh = refresh,
                        expires = json.optString("expires"),
                        session = json.optString("session")
                    )
                } else null
            }.getOrNull()
            if (parsed != null) return parsed
        }

        val redeemed = redeem(entry.account)
        if (redeemed != null) {
            save(hex, redeemed)
            return redeemed
        }

        val access = manager.peekAuthToken(entry.account, ACCESS)?.let(::open).orEmpty()
        val refresh = manager.getPassword(entry.account)?.let(::open).orEmpty()
        if (access.isNotEmpty() || refresh.isNotEmpty()) {
            val fallback = Tokens(
                token = access,
                refresh = refresh,
                expires = manager.getUserData(entry.account, EXPIRES).orEmpty(),
                session = manager.getUserData(entry.account, SESSION).orEmpty()
            )
            save(hex, fallback)
            return fallback
        }
        return null
    }

    /**
     * True while the stored access token of [hex] has more than [skew] seconds
     * left (`expires` is the lifetime in seconds counted from when it was saved).
     * Switching to such an account needs no network (ADK-AUTH-002 section 5.5).
     */
    fun fresh(hex: String, now: Long = System.currentTimeMillis(), skew: Long = 30): Boolean {
        val entry = find(hex) ?: return false
        val saved = manager.getUserData(entry.account, SAVED)?.toLongOrNull() ?: return false
        val lifetime = manager.getUserData(entry.account, EXPIRES)?.toLongOrNull() ?: return false
        return tokens(hex)?.token.isNullOrEmpty().not() && saved + (lifetime - skew) * 1000 > now
    }

    /** True for an account whose refresh token is gone: the switcher shows "sign in again" and only that account is asked. */
    fun needsSignIn(hex: String): Boolean = tokens(hex)?.refresh.isNullOrEmpty()

    /** Forgets the access token of [hex] (after a 401); the refresh token stays. */
    fun invalidate(hex: String) {
        val entry = find(hex) ?: return
        tokensPrefs.getString(hex, null)?.let { stored ->
            runCatching {
                val json = JSONObject(stored)
                json.put("token", "")
                tokensPrefs.edit().putString(hex, json.toString()).apply()
            }
        }
        manager.peekAuthToken(entry.account, ACCESS)?.let { manager.invalidateAuthToken(TYPE, it) }
    }

    /** Deposits a single-use peer session grant for [target] companion app package. */
    fun grant(hex: String, target: String): Boolean {
        val entry = find(hex) ?: return false
        val current = tokens(hex) ?: return false
        return grant(entry.account, target, current)
    }

    /** Deposits a single-use peer session grant for [target] on [account]. */
    fun grant(account: Account, target: String, tokens: Tokens, ttl: Long = TTL): Boolean {
        val json = JSONObject().apply {
            put("token", tokens.token)
            put("refresh", tokens.refresh)
            put("expires", tokens.expires)
            put("session", tokens.session)
            put("created", System.currentTimeMillis())
            put("ttl", ttl)
        }
        manager.setUserData(account, slot(target), json.toString())
        return true
    }

    /** Redeems a single-use peer session grant for this app, or null when none exists or expired. */
    fun redeem(hex: String): Tokens? {
        val entry = find(hex) ?: return null
        return redeem(entry.account)
    }

    /** Redeems a single-use peer session grant on [account] for this app package. */
    fun redeem(account: Account): Tokens? {
        val key = slot(packageName)
        val raw = manager.getUserData(account, key) ?: return null
        manager.setUserData(account, key, null)
        return runCatching {
            val json = JSONObject(raw)
            val created = json.optLong("created", 0L)
            val ttl = json.optLong("ttl", TTL)
            if (created > 0 && System.currentTimeMillis() - created > ttl) {
                return null
            }
            val token = json.optString("token")
            val refresh = json.optString("refresh")
            if (token.isEmpty() && refresh.isEmpty()) return null
            Tokens(
                token = token,
                refresh = refresh,
                expires = json.optString("expires"),
                session = json.optString("session")
            )
        }.getOrNull()
    }

    /** Revokes an unredeemed peer grant for [target]. */
    fun revoke(hex: String, target: String): Boolean {
        val entry = find(hex) ?: return false
        manager.setUserData(entry.account, slot(target), null)
        return true
    }

    /**
     * Grants account visibility to companion [packages] (e.g. apps by the same publisher)
     * using [AccountManager.setAccountVisibility] on Android 8+ (API 26+).
     *
     * Returns the count of accounts whose visibility was updated.
     */
    fun share(packages: List<String>): Int {
        if (packages.isEmpty()) return 0
        var count = 0
        for (entry in list()) {
            if (share(entry.account, packages)) count++
        }
        return count
    }

    /**
     * Grants account visibility for the account with [hex] to companion [packages].
     */
    fun share(hex: String, packages: List<String>): Boolean {
        val entry = find(hex) ?: return false
        return share(entry.account, packages)
    }

    /**
     * Grants visibility for [account] to companion [packages] and deposits peer grants.
     */
    fun share(account: Account, packages: List<String>): Boolean {
        if (packages.isEmpty()) return true
        var ok = true
        for (pkg in packages) {
            if (!visibility.set(account, pkg, AccountManager.VISIBILITY_VISIBLE)) {
                ok = false
            }
        }
        val hex = manager.getUserData(account, HEX)
        if (hex != null) {
            val current = tokens(hex)
            if (current != null) {
                for (pkg in packages) {
                    if (pkg != packageName) {
                        grant(account, pkg, current)
                    }
                }
            }
        }
        return ok
    }

    /**
     * Checks account visibility for [target] package on Android 8+ (API 26+).
     */
    fun visibility(hex: String, target: String): Int {
        val entry = find(hex) ?: return AccountManager.VISIBILITY_NOT_VISIBLE
        return visibility(entry.account, target)
    }

    /**
     * Checks account visibility for [target] package on Android 8+ (API 26+).
     */
    fun visibility(account: Account, target: String): Int =
        visibility.get(account, target)

    private fun slot(target: String): String = "grant.$target"

    /**
     * Registers this device's Keystore key as a device credential at Aduki ID (`ES256`, 65-byte
     * uncompressed point, see `Center.register`) and keeps the credential id. [bearer] is an access token
     * for audience `id` and [code] a fresh TOTP code; [center] true registers it for the account's center.
     * The key is [AndroidKey.DEVICE], a different alias from the DPoP key, so that revoking one does not
     * unbind the other.
     */
    fun registerDevice(
        client: OkHttpClient,
        identity: String,
        bearer: String,
        device: String,
        code: String,
        center: Boolean = true,
        key: Key = AndroidKey.open(AndroidKey.DEVICE)
    ): String {
        val credential = Center.register(client, identity, bearer, device, key, code, center)
        this.center.saveCredential(credential)
        return credential
    }

    /**
     * Reopens the sessions of every account the registered credential opens, with the device key and no
     * password: challenge, sign the raw nonce, unlock (`Center.unlock`), then [save] each session. Returns
     * the account hexes whose tokens were stored; sessions of accounts that have no entry here are not
     * stored (call [refresh] or [reconcile] first). One attempt: a 401 ([AdukiException.Unauthorized]) is
     * final for that nonce, the caller decides whether to ask again. A 404 means the credential was
     * revoked or lapsed (60 days): it is forgotten here and the user must sign in fully.
     *
     * @throws IllegalStateException when no credential was registered on this device.
     */
    fun unlockWithDevice(
        client: OkHttpClient,
        identity: String,
        audience: String = pro.aduki.core.config.Endpoints.AUDIENCE,
        key: Key = AndroidKey.open(AndroidKey.DEVICE)
    ): List<String> {
        val credential = center.credential() ?: throw IllegalStateException("No device credential registered")
        val opened = try {
            Center.unlock(client, identity, credential, key, audience)
        } catch (e: AdukiException.Network) {
            if (e.code == 404) center.clearCredential()
            throw e
        }
        return opened.filter { save(it.account, it.tokens) }.map { it.account }
    }

    /**
     * Revokes this device's registered credential at Aduki ID (`DELETE /credentials/{id}`) and clears it locally.
     * Returns false when no credential was registered or the credential was already gone (404).
     */
    fun revokeDevice(
        client: OkHttpClient,
        identity: String,
        bearer: String
    ): Boolean {
        val cred = center.credential() ?: return false
        val ok = Center.revoke(client, identity, bearer, cred)
        center.clearCredential()
        return ok
    }

    /** The device credential id last registered, or null. */
    fun credential(): String? = center.credential()

    /** The center token for calls to Aduki ID only, or null when it is missing or expired. */
    fun centerToken(now: Long = System.currentTimeMillis() / 1000): String? = center.token(now)

    /** Removes [hex] and its tokens. */
    fun remove(hex: String): Boolean = find(hex)?.let { drop(it); true } ?: false

    /** Removes every entry and the center token (sign-out of the device). */
    fun clear() {
        tokensPrefs.edit().clear().apply()
        list().forEach(::drop)
        center.clear()
    }

    private fun create(linked: Linked, centerId: String): Boolean =
        // one Binder call with all user data: AccountManager calls are slow on weak devices
        manager.addAccountExplicitly(Account(linked.handle, TYPE), null, Bundle().apply {
            putString(HEX, linked.hex)
            putString(CENTER, centerId)
            putString(PERSONAL, linked.personal.toString())
            linked.tenant?.let { putString(TENANT, it) }
            putString(UNLOCK, linked.unlock)
        })

    private fun write(account: Account, linked: Linked, centerId: String) {
        manager.setUserData(account, HEX, linked.hex)
        manager.setUserData(account, CENTER, centerId)
        manager.setUserData(account, PERSONAL, linked.personal.toString())
        manager.setUserData(account, TENANT, linked.tenant)
        manager.setUserData(account, UNLOCK, linked.unlock)
    }

    private fun read(account: Account): Entry? {
        val hex = manager.getUserData(account, HEX) ?: return null
        return Entry(
            account = account,
            hex = hex,
            handle = account.name,
            personal = manager.getUserData(account, PERSONAL) == "true",
            tenant = manager.getUserData(account, TENANT),
            unlock = manager.getUserData(account, UNLOCK) ?: "self",
            center = manager.getUserData(account, CENTER).orEmpty()
        )
    }

    // Removing the account deletes its password, auth tokens and user data with it.
    private fun drop(entry: Entry) {
        tokensPrefs.edit().remove(entry.hex).apply()
        manager.removeAccountExplicitly(entry.account)
    }

    private fun seal(text: String): String = vault?.sealText(text) ?: text
    private fun open(stored: String): String = try {
        vault?.openText(stored) ?: stored
    } catch (_: Exception) {
        ""
    }

    companion object {
        /** The AccountManager account type of ADK-AUTH-002 section 5.5. */
        const val TYPE = "pro.aduki.account"

        /** The auth token type of the cached access token. */
        const val ACCESS = "access"

        private const val TTL = 86_400_000L
        private const val HEX = "hex"
        private const val CENTER = "center"
        private const val PERSONAL = "personal"
        private const val TENANT = "tenant"
        private const val UNLOCK = "unlock"
        private const val EXPIRES = "expires"
        private const val SESSION = "session"
        private const val SAVED = "saved"
    }
}
