package pro.aduki.android.account

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.AccountProfile
import pro.aduki.core.models.CenterView
import pro.aduki.sdk.Aduki
import pro.aduki.sdk.Opened

/**
 * AccountsCenter is the Aduki Accounts Center on the device (plan section
 * 10): every account signed in on this device ([AccountDirectory]), linked to
 * the person's Account Center or not, with this app's state for each.
 *
 * - **Per app**: tokens are per (app, account). Switching is per app unless
 *   asked for every app; "Sign out" is this app only.
 * - **No shared authority**: linking grants nothing; each account keeps its
 *   own credentials, sessions and tenant policy. A device-only account (its
 *   tenant forbids links, or it would be a second personal account) is kept
 *   with the reason, never dropped by [refresh].
 *
 * [sessions] gives this app's client of each account; [broker] (optional)
 * tells the siblings about "use in all apps" and device sign-outs.
 */
class AccountsCenter(
    context: Context,
    val directory: AccountDirectory,
    private val sessions: Sessions,
    private val broker: SuiteBroker? = null
) {
    /** This app's sessions, one per account. */
    interface Sessions {
        /** This app's client of [hex] while it holds a session of it, else null. */
        fun client(hex: String): Aduki?

        /**
         * Opens this app's session of [hex] without a password: its own stored
         * session, else a sibling's code (`SuiteBroker.open`). Null when none
         * could; the switch then asks to sign in again. Default: none.
         */
        suspend fun open(hex: String): Aduki? = null
    }

    /** This app's state for an account. */
    enum class State {
        /** This app holds a session of it. */
        Ready,

        /** This app holds none: switching opens one through a sibling, else asks for this account's password. */
        SignInAgain
    }

    /** One row of the Accounts Center. */
    data class CenterAccount(val account: DeviceAccount, val state: State, val active: Boolean) {
        val profile: AccountProfile get() = account.profile
        val hex: String get() = account.hex
        val linked: Boolean get() = account.linked

        /** The tenant that removed it from the center, by name. */
        val removedBy: String? get() = account.removedBy

        /** Why it is not linked. */
        val refusal: Refusal? get() = account.refusal

        /** True when only its own credential opens it (its tenant fixes `unlock = self`). */
        val locked: Boolean get() = profile.unlock == "self"
    }

    /** The outcome of [add]. */
    sealed interface AddResult {
        val account: DeviceAccount

        /** The account is in the Accounts Center. */
        data class Linked(override val account: DeviceAccount, val center: CenterView?) : AddResult

        /** The account is on this device only; [reason] says why. */
        data class DeviceOnly(override val account: DeviceAccount, val reason: Refusal) : AddResult
    }

    /** The outcome of [switch]. */
    sealed interface SwitchResult {
        /** This app now uses [client]. */
        data class Ready(val client: Aduki) : SwitchResult

        /** No session could be opened: ask for this account's password (only this account). */
        data class SignInAgain(val hex: String) : SwitchResult

        /** The account is not on this device. */
        object Unknown : SwitchResult
    }

    private val prefs = context.applicationContext.getSharedPreferences(ACTIVE, Context.MODE_PRIVATE)
    private val rows = MutableStateFlow(snapshot())

    /** The accounts on this device, personal first then by tenant name; refreshed by every call here and by [watch]. */
    val accounts: StateFlow<List<CenterAccount>> = rows.asStateFlow()

    /** The account this app uses, or null. */
    fun active(): String? = prefs.getString(KEY, null)

    /** Re-reads the directory into [accounts]. */
    fun reload() {
        rows.value = snapshot()
    }

    /** Keeps [accounts] current with changes made by other apps or Settings, while [scope] lives. */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        directory.changes().collect { rows.value = snapshot(it) }
    }

    /**
     * Fetches the Account Center (`GET /v1/center`) and this account's
     * profile (`GET /v1/me`) with the active account's session (else any
     * session this app holds) and merges them into the directory: listed
     * accounts are linked, others kept as device-only.
     */
    suspend fun refresh(): Result<Unit> = attempt {
        val client = active()?.let(sessions::client)
            ?: directory.list().firstNotNullOfOrNull { sessions.client(it.hex) }
            ?: throw IllegalStateException("No session in this app")
        val me = client.account(refresh = true) ?: throw AdukiException.Network("The profile could not be read")
        directory.upsert(me)
        directory.reconcile(client.center(), me.hex)
        Unit
    }

    /**
     * Records an account just signed in in this app ([added], from
     * `Aduki.signIn(..., link = true)`) and links it to the Account Center of
     * [active] (this app's current account, null for the first account) with
     * the sign-in's [ticket]. Refused by policy (`403` the tenant forbids
     * links, `409` a second personal account) or without a ticket, the account
     * is still added, device-only, with the reason. The added account does
     * not become active: call [switch].
     *
     * @throws AdukiException when the added account's profile cannot be read.
     */
    suspend fun add(active: Aduki?, added: Aduki, ticket: String?): AddResult {
        val profile = added.account(refresh = true)
            ?: throw AdukiException.Network("The new account's profile could not be read")
        if (active == null || ticket == null) {
            val linked = profile.center != null
            val account = directory.upsert(profile, linked, refusal = if (linked) null else Refusal.NotAsked)
            reload()
            return if (linked) AddResult.Linked(account, null) else AddResult.DeviceOnly(account, Refusal.NotAsked)
        }
        val reason = try {
            val view = active.link(ticket)
            active.account(refresh = true)?.let { me ->
                directory.upsert(me)
                directory.reconcile(view, me.hex)
            }
            val account = directory.upsert(profile.copy(center = view.center), true)
            reload()
            return AddResult.Linked(account, view)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdukiException.Network) {
            when (e.code) {
                403 -> Refusal.TenantDenies
                409 -> Refusal.SecondPersonal
                else -> Refusal.Failed
            }
        } catch (e: AdukiException) {
            Refusal.Failed
        }
        val account = directory.upsert(profile, linked = false, refusal = reason)
        reload()
        return AddResult.DeviceOnly(account, reason)
    }

    /** [add] with the result of `Aduki.signIn(..., link = true)`. */
    suspend fun add(active: Aduki?, opened: Opened): AddResult = add(active, opened.client, opened.ticket)

    /**
     * Makes [hex] this app's account: its session here, else one opened
     * without a password ([Sessions.open]), else [SwitchResult.SignInAgain].
     * With [allApps] the siblings are asked to switch too ("Use in all Aduki
     * apps"; the default is this app only).
     */
    suspend fun switch(hex: String, allApps: Boolean = false): SwitchResult {
        if (directory.find(hex) == null) return SwitchResult.Unknown
        val client = sessions.client(hex) ?: sessions.open(hex) ?: return SwitchResult.SignInAgain(hex)
        prefs.edit().putString(KEY, hex).commit()
        if (allApps) attempt { broker?.activate(hex) }
        reload()
        return SwitchResult.Ready(client)
    }

    /**
     * Signs out of [hex] in this app only (its session here is revoked); the
     * account stays on the device and in the center. True when Aduki ID
     * revoked it.
     */
    suspend fun signOut(hex: String): Boolean {
        val result = sessions.client(hex)?.logout() ?: false
        if (active() == hex) prefs.edit().remove(KEY).commit()
        reload()
        return result
    }

    /**
     * Removes [hex] from this device for every app: its sessions of its
     * device group end (`DELETE /v1/sessions?group=`, with its own session
     * here, else `&account=` with another linked account's session), then
     * its directory entry goes. It stays linked on the server. The app also
     * drops what it keeps per account (the phone book of that account, ...).
     *
     * The siblings are told ([SuiteBrokerService.signedOut]) while the entry
     * still exists, so they can find which of their accounts the group
     * belongs to; told after, they find no entry and keep their sessions.
     *
     * @throws AdukiException when Aduki ID refused or could not be reached;
     * the entry is kept then.
     */
    suspend fun removeFromDevice(hex: String): Boolean {
        val target = directory.find(hex) ?: return false
        val group = target.profile.group
        val own = sessions.client(hex)
        when {
            own != null -> own.endDevice()
            group != null -> directory.list()
                .filter { it.hex != hex && it.linked }
                .firstNotNullOfOrNull { other -> sessions.client(other.hex) }
                ?.removeFromDevice(hex, group)
        }
        group?.let { attempt { broker?.signedOut(it) } }
        directory.remove(hex)
        if (active() == hex) prefs.edit().remove(KEY).commit()
        reload()
        return true
    }

    /**
     * Unlinks [hex] from the Account Center (`DELETE /v1/links`, [code] a
     * fresh authenticator code of that account); it stays on this device,
     * device-only. False when it was in no center.
     *
     * @throws IllegalStateException when this app holds no session of it.
     */
    suspend fun unlink(hex: String, code: String): Boolean {
        val client = sessions.client(hex) ?: throw IllegalStateException("No session of $hex in this app")
        val done = client.unlink(code)
        directory.mark(hex, linked = false, refusal = Refusal.Unlinked)
        reload()
        return done
    }

    /**
     * Signs out of every Aduki app on this device: for [hex] only (its device
     * group ends and its entry goes), or with no argument for every account
     * (every group this app can reach ends and the directory is cleared).
     * Returns how many accounts' groups ended at Aduki ID. Linked accounts
     * stay linked.
     */
    suspend fun signOutDevice(hex: String? = null): Int {
        val targets = directory.list().filter { hex == null || it.hex == hex }
        var ended = 0
        for (account in targets) {
            val done = attempt { sessions.client(account.hex)?.endDevice() == true }.getOrDefault(false)
            if (done) ended++
            account.profile.group?.let { attempt { broker?.signedOut(it) } }
            if (hex != null) directory.remove(account.hex)
        }
        if (hex == null) directory.clear()
        if (hex == null || active() == hex) prefs.edit().remove(KEY).commit()
        reload()
        return ended
    }

    /**
     * The profile of [hex] from Aduki ID (`GET /v1/me` with this app's
     * session of it), stored in the directory; the stored copy when it cannot
     * be read. Null for an account not on this device.
     */
    suspend fun profile(hex: String? = active()): AccountProfile? {
        hex ?: return null
        val fresh = sessions.client(hex)?.account(refresh = true)
        if (fresh != null && fresh.hex == hex) {
            directory.upsert(fresh)
            reload()
            return directory.find(hex)?.profile ?: fresh
        }
        return directory.find(hex)?.profile
    }

    private fun snapshot(list: List<DeviceAccount> = directory.list()): List<CenterAccount> {
        val active = active()
        return list.map { account ->
            val live = sessions.client(account.hex)?.session?.tokens?.value?.refresh?.isNotBlank() == true
            CenterAccount(account, if (live) State.Ready else State.SignInAgain, account.hex == active)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val ACTIVE = "pro.aduki.account.active"
        private const val KEY = "hex"
    }
}
