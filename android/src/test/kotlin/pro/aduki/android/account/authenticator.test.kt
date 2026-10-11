package pro.aduki.android.account

import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.aduki.android.signin.SignInActivity
import pro.aduki.android.softVault
import pro.aduki.core.models.Linked
import pro.aduki.core.models.Switcher
import pro.aduki.core.models.Tokens

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticatorTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val accounts = Accounts(context, softVault())
    private val authenticator = AdukiAuthenticator(context, accounts)

    @Before fun seed() {
        accounts.reconcile(Switcher("c1", "t", 4_000_000_000L, listOf(Linked("a1", "ada@aduki.me", true, null, "center"))))
        Authenticator.refresher = null
    }

    private fun token(bundle: Bundle) = bundle.getString(AccountManager.KEY_AUTHTOKEN)

    @Test fun aFreshAccessTokenIsReturnedWithoutRefreshing() {
        accounts.save("a1", Tokens("acc1", "ref1", "600", "s"))
        Authenticator.refresher = { _, _ -> error("must not refresh") }
        val result = authenticator.getAuthToken(null, accounts.find("a1")!!.account, "access", null)
        assertEquals("acc1", token(result))
        assertEquals("ada@aduki.me", result.getString(AccountManager.KEY_ACCOUNT_NAME))
        assertEquals("pro.aduki.account", result.getString(AccountManager.KEY_ACCOUNT_TYPE))
    }

    @Test fun aStaleTokenIsRefreshedAndTheRotatedRefreshTokenIsStored() {
        accounts.save("a1", Tokens("old", "ref1", "600", "s"), now = 0)
        var used: String? = null
        Authenticator.refresher = { _, refresh -> used = refresh; Tokens("new", "ref2", "600", "s") }
        val result = authenticator.getAuthToken(null, accounts.find("a1")!!.account, "access", null)
        assertEquals("ref1", used)
        assertEquals("new", token(result))
        assertEquals("ref2", accounts.tokens("a1")!!.refresh)
    }

    @Test fun anEndedSessionOpensSignInForThatAccountOnly() {
        accounts.save("a1", Tokens("old", "ref1", "600", "s"), now = 0)
        Authenticator.refresher = { _, _ -> null }
        val result = authenticator.getAuthToken(null, accounts.find("a1")!!.account, "access", null)
        assertNull(token(result))
        val intent = result.getParcelable<Intent>(AccountManager.KEY_INTENT)!!
        assertEquals(SignInActivity::class.java.name, intent.component!!.className)
        assertEquals("ada@aduki.me", intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
    }

    @Test fun aNetworkErrorIsReportedNotTreatedAsSignedOut() {
        accounts.save("a1", Tokens("old", "ref1", "600", "s"), now = 0)
        Authenticator.refresher = { _, _ -> throw java.io.IOException("offline") }
        val result = authenticator.getAuthToken(null, accounts.find("a1")!!.account, "access", null)
        assertEquals(AccountManager.ERROR_CODE_NETWORK_ERROR, result.getInt(AccountManager.KEY_ERROR_CODE))
        assertEquals("refresh token kept", "ref1", accounts.tokens("a1")!!.refresh)
    }

    @Test fun unknownTokenTypesAreBadArguments() {
        val result = authenticator.getAuthToken(null, accounts.find("a1")!!.account, "center", null)
        assertEquals(AccountManager.ERROR_CODE_BAD_ARGUMENTS, result.getInt(AccountManager.KEY_ERROR_CODE))
    }

    @Test fun addAccountOpensTheSignInActivity() {
        val result = authenticator.addAccount(null, "pro.aduki.account", null, null, null)
        assertNotNull(result.getParcelable<Intent>(AccountManager.KEY_INTENT))
    }

    @Test fun theManifestDeclaresTheAuthenticatorService() {
        val found = context.packageManager.queryIntentServices(Intent("android.accounts.AccountAuthenticator"), PackageManager.GET_META_DATA)
        assertEquals(listOf(AdukiAuthenticatorService::class.java.name), found.map { it.serviceInfo.name })
        assertTrue(!found.single().serviceInfo.exported)
        assertNotNull(found.single().serviceInfo.metaData.getInt("android.accounts.AccountAuthenticator"))
    }
}
