package pro.aduki.android.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import pro.aduki.core.models.Tokens

/**
 * AdukiAuthenticator is the `pro.aduki.account` authenticator (K4): it lets
 * Android list the accounts under [Accounts.TYPE], hands out the cached access
 * token and asks the app to refresh when it is stale.
 *
 * The app registers how to refresh with [Authenticator.refresher] (one DPoP
 * bound call to Aduki ID); adding an account opens [Authenticator.signIn], the
 * activity that starts the Custom Tabs sign-in.
 *
 * The type is declared with `customTokens`: Android neither caches tokens for
 * it nor hands a cached one to another app, so every request reaches
 * [getAuthToken], which answers by caller (`KEY_CALLER_UID`): the access token
 * only to this app and apps signed with the same key; the public profile
 * ([PartnerAccounts.PROFILE]) also to [Partners]; nothing to anyone else.
 */
class AdukiAuthenticator internal constructor(
    private val context: Context,
    private val accounts: Accounts,
    private val callers: (Int) -> Caller
) : AbstractAccountAuthenticator(context) {

    constructor(context: Context, accounts: Accounts = Accounts(context)) :
        this(context, accounts, { uid -> Caller.of(context, uid) })

    override fun addAccount(
        response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?
    ): Bundle {
        val intent = Intent(context, Authenticator.signIn).putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account, authTokenType: String, options: Bundle?): Bundle {
        // AccountManager always puts the caller's uid in; a direct call (no options) is this process.
        val uid = options?.takeIf { it.containsKey(AccountManager.KEY_CALLER_UID) }?.getInt(AccountManager.KEY_CALLER_UID)
        val caller = if (uid == null) Caller.Sibling else callers(uid)
        if (authTokenType == PartnerAccounts.PROFILE) return profile(account, caller)
        if (caller != Caller.Sibling) return error(AccountManager.ERROR_CODE_BAD_REQUEST, "Not permitted")
        val entry = accounts.list().firstOrNull { it.account == account }
        if (entry == null || authTokenType != Accounts.ACCESS) return error(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Unknown account or token type")
        var tokens = if (accounts.fresh(entry.hex)) accounts.tokens(entry.hex) else null
        if (tokens == null) {
            val refresh = accounts.tokens(entry.hex)?.refresh.orEmpty()
            val refresher = Authenticator.refresher
            if (refresh.isNotEmpty() && refresher != null) {
                tokens = try {
                    refresher(entry, refresh)?.also { accounts.save(entry.hex, it) }
                } catch (_: java.io.IOException) {
                    return error(AccountManager.ERROR_CODE_NETWORK_ERROR, "Network error")
                }
            }
        }
        if (tokens == null || tokens.token.isEmpty()) return signInAgain(response, account)
        return Bundle().apply {
            putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            putString(AccountManager.KEY_AUTHTOKEN, tokens.token)
        }
    }

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle =
        signInAgain(response, account)

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = Bundle()

    override fun getAuthTokenLabel(authTokenType: String?): String? = authTokenType

    // The public profile of the entry, for siblings and partners: never a token, never the session or device group.
    private fun profile(account: Account, caller: Caller): Bundle {
        if (caller == Caller.Other) return error(AccountManager.ERROR_CODE_BAD_REQUEST, "Not permitted")
        val found = AccountDirectory(context).list().firstOrNull { it.profile.handle.equals(account.name, ignoreCase = true) }
            ?: return error(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Unknown account")
        val json = PartnerAccounts.profile(found)
        return Bundle().apply {
            putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            putString(AccountManager.KEY_AUTHTOKEN, json)
            putString(PartnerAccounts.KEY_PROFILE, json)
        }
    }

    private fun signInAgain(response: AccountAuthenticatorResponse?, account: Account?): Bundle {
        val intent = Intent(context, Authenticator.signIn)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            .putExtra(AccountManager.KEY_ACCOUNT_NAME, account?.name)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    private fun error(code: Int, message: String) = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, code)
        putString(AccountManager.KEY_ERROR_MESSAGE, message)
    }
}

/** The service Android binds to for the `pro.aduki.account` type (declared in the library manifest). */
class AdukiAuthenticatorService : Service() {
    private val authenticator by lazy { AdukiAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/** App wiring for the authenticator. */
object Authenticator {
    /** Refreshes the session of an entry: returns the new tokens, null when the session ended, throws IOException on network errors. */
    @Volatile
    var refresher: ((Accounts.Entry, String) -> Tokens?)? = null

    /** The activity [AdukiAuthenticator.addAccount] opens to sign in (defaults to the library's [pro.aduki.android.signin.SignInActivity]). */
    @Volatile
    var signIn: Class<out android.app.Activity> = pro.aduki.android.signin.SignInActivity::class.java
}
