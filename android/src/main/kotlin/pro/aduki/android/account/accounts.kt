package pro.aduki.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import okhttp3.OkHttpClient
import org.json.JSONObject
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
 * Accounts keeps this app's session tokens of the device's accounts, and the
 * device credential, next to the [AccountManager] entries under the one
 * [TYPE] (ADK-AUTH-002 section 5.5). The entries themselves (profile, device
 * group, link state) are written by [AccountDirectory]; [AccountsCenter]
 * keeps them in step with Aduki ID.
 *
 * ## Where session tokens live
 * The [mode] decides, and there is always exactly one copy:
 * - [Mode.Own] (default): this app's own session, sealed with the mode's [Vault]
 *   in this app's private storage. The account entry carries metadata only
 *   (see [AccountDirectory]). Another app of the same publisher opens its own
 *   session with a handoff code a signed-in sibling mints for its key on
 *   request (`SuiteBroker.open`). Only that single-use code crosses apps.
 * - [Mode.Shared]: one session for every app the entry is visible to (apps that
 *   share a `TokenStore`). The tokens live only in the account entry, unsealed,
 *   because a Keystore key belongs to one app. Every read goes to the entry, so
 *   a renewal by one app is what the next app reads.
 *
 * All apps of one publisher should use the same mode.
 *
 * ## Cross-app visibility
 * On Android 8+ (API 26+) an account is invisible to other apps until [share]
 * grants it to their packages ([Visibility] is the seam tests replace).
 */
class Accounts(
    context: Context,
    /** Where session tokens are kept; see [Mode]. */
    val mode: Mode = Mode.Own(),
    private val manager: AccountManager = AccountManager.get(context),
    private val center: CenterStore = CenterStore(context, mode.vault),
    private val visibility: Visibility = Visibility.system(manager)
) {
    private val packageName: String = context.packageName
    private val tokensPrefs = context.applicationContext.getSharedPreferences(TOKENS, Context.MODE_PRIVATE)

    /** Where an [Accounts] keeps session tokens. */
    sealed class Mode(val vault: Vault?) {
        /**
         * One session shared by the apps the entry is visible to: the tokens
         * are kept only in the AccountManager entry, unsealed (the system
         * account database is readable only by apps it is made visible to).
         */
        object Shared : Mode(null)

        /**
         * This app's own session: the tokens are kept only in this app's
         * private storage, sealed with [vault] (the Keystore on a device).
         */
        class Own(vault: Vault? = Vault.platform()) : Mode(vault)
    }

    /** What [read] found for an account. */
    sealed interface Stored {
        /** The tokens, and when they were saved (epoch milliseconds, 0 when unknown). */
        data class Found(val tokens: Tokens, val saved: Long) : Stored

        /** Nothing is stored: the account is signed out in this app. */
        object Missing : Stored

        /**
         * Tokens are stored but are not this app's to use: sealed by another
         * app or with a Keystore key that is gone, or (for [migrate]) left
         * unsealed by an app in [Mode.Shared]. Sign in again.
         */
        object Sealed : Stored
    }

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

    /** All entries of this type, personal first then by handle. */
    fun list(): List<Entry> = manager.getAccountsByType(TYPE).mapNotNull(::read)
        .sortedWith(compareByDescending<Entry> { it.personal }.thenBy { it.handle })

    fun find(hex: String): Entry? = list().firstOrNull { it.hex == hex }

    /** Stores the session [tokens] of the account [hex] where the [mode] keeps them; false when no such entry exists. */
    fun save(hex: String, tokens: Tokens, now: Long = System.currentTimeMillis()): Boolean {
        val entry = find(hex) ?: return false
        when (mode) {
            is Mode.Shared -> {
                dropLegacyCopy(hex) // a 0.5.x private copy would go stale
                manager.setUserData(entry.account, SAVED, now.toString())
                manager.setUserData(entry.account, EXPIRES, tokens.expires)
                manager.setUserData(entry.account, SESSION, tokens.session)
                manager.setPassword(entry.account, tokens.refresh.ifEmpty { null })
                manager.setAuthToken(entry.account, ACCESS, tokens.token.ifEmpty { null })
            }
            is Mode.Own -> {
                val json = JSONObject().apply {
                    put("token", seal(tokens.token))
                    put("refresh", seal(tokens.refresh))
                    put("expires", tokens.expires)
                    put("session", tokens.session)
                    put("saved", now)
                    put("mode", OWN)
                }
                // commit(), not apply(): a rotated refresh token that is lost
                // when the process dies is a signed-out app (or a revoked session).
                tokensPrefs.edit().putString(hex, json.toString()).commit()
                manager.setUserData(entry.account, own(packageName), now.toString())
            }
        }
        return true
    }

    /**
     * The stored session of [hex]: [Stored.Found], [Stored.Missing] (signed
     * out here) or [Stored.Sealed] (stored, but not openable by this app).
     * Nothing is read from anywhere the [mode] does not keep tokens.
     */
    fun read(hex: String): Stored {
        val entry = find(hex) ?: return Stored.Missing
        return when (mode) {
            is Mode.Shared -> {
                dropLegacyCopy(hex)
                val access = manager.peekAuthToken(entry.account, ACCESS).orEmpty()
                val refresh = manager.getPassword(entry.account).orEmpty()
                when {
                    access.startsWith(SEALED) || refresh.startsWith(SEALED) -> Stored.Sealed
                    access.isEmpty() && refresh.isEmpty() -> Stored.Missing
                    else -> Stored.Found(
                        Tokens(
                            token = access,
                            refresh = refresh,
                            expires = manager.getUserData(entry.account, EXPIRES).orEmpty(),
                            session = manager.getUserData(entry.account, SESSION).orEmpty()
                        ),
                        manager.getUserData(entry.account, SAVED)?.toLongOrNull() ?: 0L
                    )
                }
            }
            is Mode.Own -> {
                val stored = tokensPrefs.getString(hex, null) ?: return Stored.Missing
                val json = runCatching { JSONObject(stored) }.getOrNull() ?: return Stored.Sealed
                val access = open(json.optString("token")) ?: return Stored.Sealed
                val refresh = open(json.optString("refresh")) ?: return Stored.Sealed
                if (access.isEmpty() && refresh.isEmpty()) return Stored.Missing
                Stored.Found(
                    Tokens(token = access, refresh = refresh, expires = json.optString("expires"), session = json.optString("session")),
                    json.optLong("saved", 0L)
                )
            }
        }
    }

    /** The stored session of [hex], or null when it is [Stored.Missing] or [Stored.Sealed] (see [read]). */
    fun tokens(hex: String): Tokens? = (read(hex) as? Stored.Found)?.tokens

    /**
     * Moves tokens an older SDK left in the account entry (0.5.x wrote them
     * there, sealed with this app's vault) into this app's private store, then
     * removes them from the entry. Opt-in, [Mode.Own] only: nothing written by
     * another app is adopted, because it cannot be opened here.
     */
    fun migrate(hex: String): Stored {
        val current = read(hex)
        if (mode !is Mode.Own || current !is Stored.Missing) return current
        val entry = find(hex) ?: return Stored.Missing
        val rawAccess = manager.peekAuthToken(entry.account, ACCESS).orEmpty()
        val rawRefresh = manager.getPassword(entry.account).orEmpty()
        if (rawAccess.isEmpty() && rawRefresh.isEmpty()) return Stored.Missing
        if (mode.vault != null && !(rawRefresh.startsWith(SEALED) || rawAccess.startsWith(SEALED))) return Stored.Sealed
        val access = open(rawAccess) ?: return Stored.Sealed
        val refresh = open(rawRefresh) ?: return Stored.Sealed
        val tokens = Tokens(
            token = access,
            refresh = refresh,
            expires = manager.getUserData(entry.account, EXPIRES).orEmpty(),
            session = manager.getUserData(entry.account, SESSION).orEmpty()
        )
        save(hex, tokens)
        manager.setPassword(entry.account, null)
        if (rawAccess.isNotEmpty()) manager.invalidateAuthToken(TYPE, rawAccess)
        return read(hex)
    }

    /**
     * Forgets this app's session of [hex] and keeps the account entry (the
     * refresh token is spent, or the person signed out of this app only). In
     * [Mode.Shared] that is every app's session.
     */
    fun forget(hex: String) {
        val entry = find(hex) ?: return
        when (mode) {
            is Mode.Shared -> {
                dropLegacyCopy(hex)
                manager.peekAuthToken(entry.account, ACCESS)?.let { manager.invalidateAuthToken(TYPE, it) }
                manager.setPassword(entry.account, null)
            }
            is Mode.Own -> {
                tokensPrefs.edit().remove(hex).apply()
                manager.setUserData(entry.account, own(packageName), null)
            }
        }
    }

    /**
     * This app's private tokens of [hex], deleted on the way out, whether or
     * not the account entry still exists ([Mode.Own]): after another app or
     * system Settings removed the account, the caller can still revoke the
     * session it held. Null when this app kept none or cannot open them.
     */
    fun discard(hex: String): Tokens? {
        if (mode !is Mode.Own) return null
        val stored = tokensPrefs.getString(hex, null) ?: return null
        tokensPrefs.edit().remove(hex).commit()
        find(hex)?.let { manager.setUserData(it.account, own(packageName), null) }
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: return null
        val access = open(json.optString("token")) ?: return null
        val refresh = open(json.optString("refresh")) ?: return null
        if (access.isEmpty() && refresh.isEmpty()) return null
        return Tokens(token = access, refresh = refresh, expires = json.optString("expires"), session = json.optString("session"))
    }

    // Mode.Shared drops a private copy 0.5.x kept next to the entry, never a Mode.Own app's
    // session: 0.6.1 marks those, and the unmarked ones 0.6.0 wrote are sealed (0.5.x suites kept them unsealed).
    private fun dropLegacyCopy(hex: String) {
        val stored = tokensPrefs.getString(hex, null) ?: return
        val json = runCatching { JSONObject(stored) }.getOrNull()
        val own = json != null && (json.optString("mode") == OWN || json.optString("refresh").startsWith(SEALED))
        if (!own) tokensPrefs.edit().remove(hex).apply()
    }

    /**
     * True while the stored access token of [hex] has more than [skew] seconds
     * left (`expires` is the lifetime in seconds counted from when it was saved).
     * Switching to such an account needs no network (ADK-AUTH-002 section 5.5).
     */
    fun fresh(hex: String, now: Long = System.currentTimeMillis(), skew: Long = 30): Boolean {
        val found = read(hex) as? Stored.Found ?: return false
        val lifetime = found.tokens.expires.toLongOrNull() ?: return false
        return found.tokens.token.isNotEmpty() && found.saved > 0 && found.saved + (lifetime - skew) * 1000 > now
    }

    /** True for an account whose refresh token is gone: the switcher shows "sign in again" and only that account is asked. */
    fun needsSignIn(hex: String): Boolean = tokens(hex)?.refresh.isNullOrEmpty()

    /** Forgets the access token of [hex] (after a 401); the refresh token stays. */
    fun invalidate(hex: String) {
        val entry = find(hex) ?: return
        when (mode) {
            is Mode.Shared -> manager.peekAuthToken(entry.account, ACCESS)?.let { manager.invalidateAuthToken(TYPE, it) }
            is Mode.Own -> tokensPrefs.getString(hex, null)?.let { stored ->
                runCatching {
                    val json = JSONObject(stored)
                    json.put("token", "")
                    tokensPrefs.edit().putString(hex, json.toString()).commit()
                }
            }
        }
    }

    /**
     * The handoff an older sibling (push path) left for this app on [hex],
     * without removing it: redeem it, then [settle] it whatever the outcome
     * except a network failure (then it stays for the next try). Its expiry
     * is not checked here: it was written on the sibling's view of the server
     * clock, and Aduki ID decides (`401 auth.invalid` for an expired code).
     */
    fun pending(hex: String): String? {
        val entry = find(hex) ?: return null
        val raw = manager.getUserData(entry.account, slot(packageName)) ?: return null
        return runCatching { JSONObject(raw).getString("code") }.getOrNull()?.ifBlank { null }
    }

    /**
     * Removes the handoff [code] left for this app on [hex] after it was
     * redeemed or refused. A newer code left meanwhile is kept. False when
     * that code is no longer there.
     */
    fun settle(hex: String, code: String): Boolean {
        val entry = find(hex) ?: return false
        if (pending(hex) != code) return false
        manager.setUserData(entry.account, slot(packageName), null)
        return true
    }

    /**
     * Moves this app's private tokens of [from] to [to] (the migration of an
     * entry keyed by email to the account's real hex, `AccountDirectory.migrate`).
     * False when nothing was stored under [from] or [to] already has tokens.
     */
    fun rekey(from: String, to: String): Boolean {
        if (from == to) return false
        val stored = tokensPrefs.getString(from, null) ?: return false
        if (tokensPrefs.contains(to)) return false
        return tokensPrefs.edit().putString(to, stored).remove(from).commit()
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

    /** Grants visibility of the account [hex] to companion [packages]. */
    fun share(hex: String, packages: List<String>): Boolean {
        val entry = find(hex) ?: return false
        return share(entry.account, packages)
    }

    /** Grants visibility of [account] to companion [packages]. No tokens are handed over. */
    fun share(account: Account, packages: List<String>): Boolean {
        var ok = true
        for (pkg in packages) {
            if (!visibility.set(account, pkg, AccountManager.VISIBILITY_VISIBLE)) ok = false
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

    private fun slot(target: String): String = "handoff.$target"
    private fun own(target: String): String = "own.$target"

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
     * stored (add them with `AccountDirectory.upsert` or `AccountsCenter.refresh` first). One attempt: a 401 ([AdukiException.Unauthorized]) is
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

    /** Removes [hex] and its tokens. */
    fun remove(hex: String): Boolean = find(hex)?.let { drop(it); true } ?: false

    /** Removes every entry, this app's tokens and the device credential (sign-out of the device). */
    fun clear() {
        tokensPrefs.edit().clear().apply()
        list().forEach(::drop)
        center.clear()
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

    private fun seal(text: String): String = mode.vault?.sealText(text) ?: text

    // Null when [stored] is sealed and this app cannot open it.
    private fun open(stored: String): String? = try {
        if (stored.startsWith(SEALED) && mode.vault == null) null else mode.vault?.openText(stored) ?: stored
    } catch (_: Exception) {
        null
    }

    companion object {
        /** The AccountManager account type of ADK-AUTH-002 section 5.5. */
        const val TYPE = "pro.aduki.account"

        /** The auth token type of the cached access token. */
        const val ACCESS = "access"

        /** This app's private file of [Mode.Own] tokens. */
        internal const val TOKENS = "pro.aduki.account.tokens"

        /** The marker of a [Mode.Own] copy in the private file. */
        private const val OWN = "own"

        /** The prefix of text sealed by a [Vault]. */
        private const val SEALED = "aduki:1:"
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
