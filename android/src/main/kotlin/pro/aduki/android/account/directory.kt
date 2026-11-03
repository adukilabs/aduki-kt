package pro.aduki.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import pro.aduki.core.models.AccountProfile
import pro.aduki.core.models.CenterView
import pro.aduki.core.models.Tenancy

/**
 * Why an account on this device is not in the Account Center.
 */
enum class Refusal {
    /** The account's tenant forbids links (`403`). */
    TenantDenies,

    /** The center already holds a personal account (`409`). */
    SecondPersonal,

    /** Linking was not asked for ("Add to your Aduki Accounts Center" was off). */
    NotAsked,

    /** The account was unlinked (by the person, or its tenant: see [DeviceAccount.removedBy]). */
    Unlinked,

    /** The link could not be made (the ticket expired, or Aduki ID could not be reached). */
    Failed
}

/**
 * One account signed in on this device, as every suite app sees it: its
 * [profile] (real hex, handle, name, kind, tenant name and role, photo tag,
 * device [AccountProfile.group]), whether it is [linked] to the Account
 * Center, the tenant that removed it from the center ([removedBy], its
 * name), and why it is not linked ([refusal]).
 */
data class DeviceAccount(
    val profile: AccountProfile,
    val linked: Boolean,
    val removedBy: String? = null,
    val refusal: Refusal? = null
) {
    val hex: String get() = profile.hex
}

/**
 * AccountDirectory is the device's account directory (ADK-AUTH-002 §5.5,
 * plan section 3): one [AccountManager] entry per account signed in on this
 * device, under [Accounts.TYPE], holding profile data only (never a token).
 * Every suite app reads it; each app also keeps a private mirror, so entries
 * purged by the system (the app owning the authenticator was uninstalled)
 * can be rebuilt ([orphans], [restore]) instead of being read as "signed out".
 *
 * The entry's name stays the handle (what Settings > Accounts shows). Its
 * user data is version 2: `v`, `hex` (the real account hex), `handle`,
 * `name`, `kind`, `tenant` (hex), `tenantName`, `role`, `owner`, `photo`,
 * `center`, `unlock`, `links`, `group`, `linked`, `removedBy`, `refusal`,
 * plus `personal` for [Accounts.list]. Entries an older suite wrote (keyed
 * by email, `hex` = the email) are moved to the real hex with [migrate].
 *
 * [packages] are the sibling apps every new entry is made visible to.
 */
class AccountDirectory(
    context: Context,
    private val packages: List<String> = emptyList(),
    private val manager: AccountManager = AccountManager.get(context),
    private val visibility: Visibility = Visibility.system(manager)
) {
    private val app = context.applicationContext
    private val mirror = app.getSharedPreferences(MIRROR, Context.MODE_PRIVATE)
    private val local = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** What a [reconcile] changed, by hex. */
    data class Change(val added: List<String>, val updated: List<String>, val unlinked: List<String>)

    /** An entry moved from its old key (an email) to the real account hex. */
    data class Migration(val from: String, val to: String)

    /** Every account on this device: personal first, then by tenant name, then handle. */
    @Synchronized
    fun list(): List<DeviceAccount> = entries().map { it.second }.sortedWith(ORDER)

    /** The account [hex], or null. */
    fun find(hex: String): DeviceAccount? = entries().firstOrNull { it.second.hex == hex }?.second

    /**
     * Adds or updates [profile]. With [linked] null the stored link state
     * ([DeviceAccount.linked], [DeviceAccount.removedBy],
     * [DeviceAccount.refusal]) is kept (a new entry is unlinked); otherwise
     * all three are set from the arguments. A null `group` or `session` in
     * [profile] keeps the stored one (center listings carry neither).
     */
    @Synchronized
    fun upsert(
        profile: AccountProfile,
        linked: Boolean? = null,
        removedBy: String? = null,
        refusal: Refusal? = null
    ): DeviceAccount {
        require(profile.hex.isNotBlank() && profile.handle.isNotBlank()) { "an account needs a hex and a handle" }
        val have = entries().firstOrNull { it.second.hex == profile.hex }
        val old = have?.second
        val merged = DeviceAccount(
            profile = profile.copy(
                group = profile.group ?: old?.profile?.group,
                session = profile.session ?: old?.profile?.session
            ),
            linked = linked ?: old?.linked ?: false,
            removedBy = if (linked == null) old?.removedBy else removedBy,
            refusal = if (linked == null) old?.refusal else refusal
        )
        val account = have?.first
        if (account == null || !account.name.equals(profile.handle, ignoreCase = true)) {
            if (account != null) manager.removeAccountExplicitly(account)
            val created = Account(profile.handle, Accounts.TYPE)
            if (!manager.addAccountExplicitly(created, null, bundle(merged))) write(created, merged)
            packages.forEach { visibility.set(created, it, AccountManager.VISIBILITY_VISIBLE) }
        } else {
            write(account, merged)
        }
        remember(merged)
        local.tryEmit(Unit)
        return merged
    }

    /** Marks [hex] linked or not, with the reason; false when there is no such account. */
    fun mark(hex: String, linked: Boolean, refusal: Refusal? = null, removedBy: String? = null): Boolean {
        val have = find(hex) ?: return false
        upsert(have.profile, linked, removedBy, refusal)
        return true
    }

    /**
     * Removes [hex] from this device for every app (entry and mirror). Its
     * sessions are not ended here: see `AccountsCenter.removeFromDevice`.
     */
    @Synchronized
    fun remove(hex: String): Boolean {
        val have = entries().firstOrNull { it.second.hex == hex }
        mirror.edit().remove(hex).commit()
        have?.let { manager.removeAccountExplicitly(it.first) }
        local.tryEmit(Unit)
        return have != null
    }

    /** Removes every account (sign out of all accounts on this device). */
    @Synchronized
    fun clear() {
        manager.getAccountsByType(Accounts.TYPE).forEach { manager.removeAccountExplicitly(it) }
        mirror.edit().clear().commit()
        local.tryEmit(Unit)
    }

    /**
     * Merges `GET /v1/center` into the directory for the account [current]
     * (plan section 10.3): accounts the center lists are added or updated as
     * linked; accounts it does not list are **kept**, unlinked (with the
     * tenant's name in [DeviceAccount.removedBy] for a member that was linked
     * to that center: its tenant removed it). Nothing is dropped here.
     */
    @Synchronized
    fun reconcile(view: CenterView, current: String): Change {
        val before = list().associateBy { it.hex }
        val added = ArrayList<String>()
        val updated = ArrayList<String>()
        val unlinked = ArrayList<String>()
        val listed = view.accounts.map { it.hex }.toSet()
        val alone = view.center == null
        for (profile in view.accounts) {
            val old = before[profile.hex]
            val next = if (alone) {
                upsert(profile.copy(center = null), false, old?.removedBy, old?.refusal)
            } else {
                upsert(profile.copy(center = view.center), true)
            }
            if (old == null) added += profile.hex else if (old != next) updated += profile.hex
        }
        val previous = before[current]?.profile?.center
        for (old in before.values) {
            if (old.hex in listed || !old.linked) continue
            val sameCenter = view.center != null && old.profile.center == view.center
            val leftCurrent = alone && previous != null && old.profile.center == previous
            if (!sameCenter && !leftCurrent) continue
            val tenant = if (sameCenter && !old.profile.personal) old.profile.tenant?.name else null
            upsert(old.profile.copy(center = null), linked = false, removedBy = tenant, refusal = Refusal.Unlinked)
            unlinked += old.hex
        }
        return Change(added, updated, unlinked)
    }

    /**
     * Moves an entry an older suite keyed by email (its `hex` is the email,
     * or it has no version) to [profile]'s real hex and writes the full
     * profile. This app's private tokens under the email (`Accounts`,
     * [Accounts.Mode.Own]) move with it. Returns the move so the app can
     * re-key its own settings (e.g. `contacts.phonebook`, the active
     * account), or null when no such entry exists. The entry is updated in
     * place, never recreated, so no app is signed out.
     */
    @Synchronized
    fun migrate(profile: AccountProfile): Migration? {
        val handle = profile.handle
        val legacy = manager.getAccountsByType(Accounts.TYPE).firstOrNull { account ->
            val v = manager.getUserData(account, V)
            val hex = manager.getUserData(account, HEX).orEmpty()
            v != VERSION && (hex.equals(handle, ignoreCase = true) || account.name.equals(handle, ignoreCase = true))
        } ?: return null
        val from = manager.getUserData(legacy, HEX).orEmpty().ifBlank { legacy.name }
        val stored = DeviceAccount(
            profile = profile,
            linked = profile.center != null,
            removedBy = null,
            refusal = null
        )
        write(legacy, stored)
        remember(stored)
        if (from != profile.hex) {
            val tokens = app.getSharedPreferences(Accounts.TOKENS, Context.MODE_PRIVATE)
            tokens.getString(from, null)?.let { json ->
                if (!tokens.contains(profile.hex)) tokens.edit().putString(profile.hex, json).remove(from).commit()
            }
            mirror.edit().remove(from).commit()
        }
        local.tryEmit(Unit)
        return Migration(from, profile.hex)
    }

    /** True while an entry an older suite wrote (no version 2 data) is on the device. */
    fun needsMigration(): Boolean =
        manager.getAccountsByType(Accounts.TYPE).any { manager.getUserData(it, V) != VERSION }

    /**
     * Accounts this app's mirror holds but the device has no entry for:
     * purged by the system (the authenticator's owner app was uninstalled)
     * or removed in another app or in Settings. Removal means "signed out of
     * the device" only when Aduki ID says the group ended; check, then
     * [restore] or [forget] each.
     */
    fun orphans(): List<DeviceAccount> {
        val present = entries().map { it.second.hex }.toSet()
        return mirror.all.keys.filter { it !in present }.mapNotNull(::mirrored)
    }

    /** Recreates the entry of [hex] from this app's mirror; false when the mirror has none. */
    fun restore(hex: String): Boolean {
        val copy = mirrored(hex) ?: return false
        upsert(copy.profile, copy.linked, copy.removedBy, copy.refusal)
        return true
    }

    /** Drops [hex] from this app's mirror only. */
    fun forget(hex: String) {
        mirror.edit().remove(hex).commit()
    }

    /**
     * The directory now and after every change: entries added or removed by
     * any app or Settings (`OnAccountsUpdateListener`), and every write
     * through this instance. User data written by another app is seen at the
     * next emission or [list].
     */
    fun changes(): Flow<List<DeviceAccount>> = callbackFlow {
        val listener = OnAccountsUpdateListener { trySend(list()) }
        manager.addOnAccountsUpdatedListener(listener, null, true)
        val writes = launch { local.collect { trySend(list()) } }
        awaitClose {
            writes.cancel()
            runCatching { manager.removeOnAccountsUpdatedListener(listener) }
        }
    }

    private fun entries(): List<Pair<Account, DeviceAccount>> =
        manager.getAccountsByType(Accounts.TYPE).mapNotNull { account -> read(account)?.let { account to it } }

    private fun read(account: Account): DeviceAccount? {
        fun get(key: String) = manager.getUserData(account, key)?.ifBlank { null }
        val hex = get(HEX) ?: return null
        if (get(V) != VERSION) {
            // An older entry: what it has (the handle as hex until migrated).
            val personal = get(PERSONAL) != "false"
            return DeviceAccount(
                AccountProfile(
                    hex = hex,
                    handle = account.name,
                    kind = if (personal) AccountProfile.Kind.Personal else AccountProfile.Kind.Member,
                    center = get(CENTER),
                    unlock = get(UNLOCK) ?: "self"
                ),
                linked = get(CENTER) != null
            )
        }
        val tenant = get(TENANT)?.let { Tenancy(it, get(TENANT_NAME).orEmpty(), get(ROLE), get(OWNER) == "true") }
        return DeviceAccount(
            profile = AccountProfile(
                hex = hex,
                handle = get(HANDLE) ?: account.name,
                name = get(NAME).orEmpty(),
                kind = AccountProfile.Kind.of(get(KIND)),
                tenant = tenant,
                photo = get(PHOTO),
                center = get(CENTER),
                unlock = get(UNLOCK) ?: "self",
                links = get(LINKS) ?: "allow",
                group = get(GROUP)
            ),
            linked = get(LINKED) == "true",
            removedBy = get(REMOVED_BY),
            refusal = get(REFUSAL)?.let { r -> Refusal.entries.firstOrNull { it.name == r } }
        )
    }

    private fun fields(a: DeviceAccount): Map<String, String?> {
        val p = a.profile
        return linkedMapOf(
            V to VERSION,
            HEX to p.hex,
            HANDLE to p.handle,
            NAME to p.name.ifBlank { null },
            KIND to p.kind.wire,
            PERSONAL to p.personal.toString(),
            TENANT to p.tenant?.hex,
            TENANT_NAME to p.tenant?.name,
            ROLE to p.tenant?.role,
            OWNER to p.tenant?.owner?.toString(),
            PHOTO to p.photo,
            CENTER to p.center.orEmpty(),
            UNLOCK to p.unlock,
            LINKS to p.links,
            GROUP to p.group,
            LINKED to a.linked.toString(),
            REMOVED_BY to a.removedBy,
            REFUSAL to a.refusal?.name
        )
    }

    private fun bundle(a: DeviceAccount) = Bundle().apply { fields(a).forEach { (k, v) -> if (v != null) putString(k, v) } }

    private fun write(account: Account, a: DeviceAccount) = fields(a).forEach { (k, v) -> manager.setUserData(account, k, v) }

    private fun remember(a: DeviceAccount) {
        val json = JSONObject()
        fields(a).forEach { (k, v) -> if (v != null) json.put(k, v) }
        json.put("account", a.profile.handle)
        mirror.edit().putString(a.hex, json.toString()).commit()
    }

    private fun mirrored(hex: String): DeviceAccount? {
        val json = runCatching { JSONObject(mirror.getString(hex, null) ?: return null) }.getOrNull() ?: return null
        fun get(key: String) = json.optString(key).ifBlank { null }
        val tenant = get(TENANT)?.let { Tenancy(it, get(TENANT_NAME).orEmpty(), get(ROLE), get(OWNER) == "true") }
        return DeviceAccount(
            AccountProfile(
                hex = hex,
                handle = get(HANDLE) ?: return null,
                name = get(NAME).orEmpty(),
                kind = AccountProfile.Kind.of(get(KIND)),
                tenant = tenant,
                photo = get(PHOTO),
                center = get(CENTER),
                unlock = get(UNLOCK) ?: "self",
                links = get(LINKS) ?: "allow",
                group = get(GROUP)
            ),
            linked = get(LINKED) == "true",
            removedBy = get(REMOVED_BY),
            refusal = get(REFUSAL)?.let { r -> Refusal.entries.firstOrNull { it.name == r } }
        )
    }

    companion object {
        /** The user-data version this directory writes. */
        const val VERSION = "2"

        private const val MIRROR = "pro.aduki.account.directory"

        private val ORDER = compareByDescending<DeviceAccount> { it.profile.personal }
            .thenBy { it.profile.tenant?.name.orEmpty().lowercase() }
            .thenBy { it.profile.handle.lowercase() }

        private const val V = "v"
        private const val HEX = "hex"
        private const val HANDLE = "handle"
        private const val NAME = "name"
        private const val KIND = "kind"
        private const val PERSONAL = "personal"
        private const val TENANT = "tenant"
        private const val TENANT_NAME = "tenantName"
        private const val ROLE = "role"
        private const val OWNER = "owner"
        private const val PHOTO = "photo"
        private const val CENTER = "center"
        private const val UNLOCK = "unlock"
        private const val LINKS = "links"
        private const val GROUP = "group"
        private const val LINKED = "linked"
        private const val REMOVED_BY = "removedBy"
        private const val REFUSAL = "refusal"
    }
}
