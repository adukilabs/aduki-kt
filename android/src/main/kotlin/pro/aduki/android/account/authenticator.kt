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
 */
class AdukiAuthenticator(private val context: Context, private val accounts: Accounts = Accounts(context)) :
    AbstractAccountAuthenticator(context) {

    override fun addAccount(
        response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?
    ): Bundle {
        val intent = Intent(context, Authenticator.signIn).putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account, authTokenType: String, options: Bundle?): Bundle {
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
