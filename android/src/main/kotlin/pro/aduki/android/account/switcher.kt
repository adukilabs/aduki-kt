package pro.aduki.android.account

/**
 * SwitcherState is what a switcher screen shows (ADK-AUTH-002 section 12 UI
 * notes): one row per linked account, personal first, a "sign in again" mark
 * where the session ended, a lock where the tenant fixes `unlock = self`, and
 * whether switching needs the network. It is plain data so it can be tested
 * without a screen; a view only renders it.
 *
 * Deprecated in 0.8.0: [AccountsCenter.accounts] has the profile (name,
 * photo, tenant name and role), the link state and this app's state. [of]
 * with a list of [AccountsCenter.CenterAccount] maps it here meanwhile.
 */
@Deprecated("Use AccountsCenter.accounts (CenterAccount rows with the full profile).")
data class SwitcherState(val rows: List<Row>, val removed: List<String>) {

    data class Row(
        val hex: String,
        val handle: String,
        val personal: Boolean,
        val tenant: String?,
        /** True when only the account's own credential opens it (lock icon). */
        val locked: Boolean,
        /** True when its refresh token is gone: ask for factors for this account only. */
        val signInAgain: Boolean,
        /** True when switching to it is local (a fresh access token is cached). */
        val instant: Boolean,
        val active: Boolean
    )

    companion object {
        /** The rows of an [AccountsCenter] ([AccountsCenter.accounts]), for screens still on this type. */
        fun of(accounts: List<AccountsCenter.CenterAccount>, removed: List<String> = emptyList()): SwitcherState =
            SwitcherState(
                accounts.map {
                    Row(
                        hex = it.hex,
                        handle = it.profile.handle,
                        personal = it.profile.personal,
                        tenant = it.profile.tenant?.hex,
                        locked = it.locked,
                        signInAgain = it.state == AccountsCenter.State.SignInAgain,
                        instant = false,
                        active = it.active
                    )
                },
                removed
            )

        /** Builds the state from [accounts]; [active] is the hex in use and [removed] the handles a [Accounts.Change] just dropped ("removed by tenant"). */
        fun of(accounts: Accounts, active: String?, removed: List<String> = emptyList(), now: Long = System.currentTimeMillis()): SwitcherState =
            SwitcherState(
                accounts.list().map {
                    Row(
                        hex = it.hex,
                        handle = it.handle,
                        personal = it.personal,
                        tenant = it.tenant,
                        locked = it.unlock == "self",
                        signInAgain = accounts.needsSignIn(it.hex),
                        instant = accounts.fresh(it.hex, now),
                        active = it.hex == active
                    )
                },
                removed
            )
    }
}
