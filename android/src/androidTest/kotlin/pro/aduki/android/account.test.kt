package pro.aduki.android

import android.accounts.AccountManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.aduki.android.account.Accounts
import pro.aduki.android.account.AdukiAuthenticatorService
import pro.aduki.android.signin.RedirectActivity
import pro.aduki.core.models.Linked
import pro.aduki.core.models.Switcher
import pro.aduki.core.models.Tokens
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider

/** The AccountManager and manifest parts against the real system services. */
@RunWith(AndroidJUnit4::class)
class AccountInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefix = "inst_acc_${System.nanoTime()}_"
    private val accounts = Accounts(context, Vault(Provider(), prefix))
    private val ada = Linked("a1b2", "ada@aduki.me", true, null, "center")
    private val work = Linked("c3d4", "ada@adalabs.dev", false, "t1", "self")
    private val contoso = Linked("e5f6", "ada@contoso.com", false, "t2", "self")

    @After fun clear() {
        accounts.clear()
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias("${prefix}1")) ks.deleteEntry("${prefix}1")
    }

    @Test fun theSystemKnowsThePro_aduki_accountAuthenticator() {
        val types = AccountManager.get(context).authenticatorTypes.map { it.type }
        assertTrue("authenticator types: $types", "pro.aduki.account" in types)
        val service = context.packageManager.queryIntentServices(Intent("android.accounts.AccountAuthenticator"), 0)
        assertTrue(service.any { it.serviceInfo.name == AdukiAuthenticatorService::class.java.name })
    }

    @Test fun accountsAreStoredSealedAndLinksDenyRemovesTheMemberQuickly() {
        accounts.reconcile(Switcher("c1", "center-token", 4_000_000_000L, listOf(ada, work, contoso)))
        accounts.save("e5f6", Tokens("adka_secret", "adkr_secret", "600", "s"))
        val manager = AccountManager.get(context)
        assertEquals(3, manager.getAccountsByType("pro.aduki.account").size)
        val entry = accounts.find("e5f6")!!
        assertTrue(manager.getPassword(entry.account)!!.startsWith("aduki:1:"))
        assertEquals("adkr_secret", accounts.tokens("e5f6")!!.refresh)
        val started = System.nanoTime()
        val change = accounts.reconcile(Switcher("c1", "center-token", 4_000_000_000L, listOf(ada, work)))
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i("AdukiAccounts", "links=deny removal on device took $ms ms")
        assertEquals(listOf("e5f6"), change.removed)
        assertNull(accounts.find("e5f6"))
        assertEquals(2, manager.getAccountsByType("pro.aduki.account").size)
        // the 1 s target is end to end on a phone (ANDROID-TESTS.md); this emulator is software-only, so only a sanity bound
        assertTrue("removal took $ms ms", ms < 10_000)
    }

    @Test fun theRedirectSchemeResolvesToTheRedirectActivityOnly() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("pro.aduki.app:/callback?code=1&state=s")).addCategory(Intent.CATEGORY_BROWSABLE)
        val found = context.packageManager.queryIntentActivities(view, 0).map { it.activityInfo.name }
        assertEquals(listOf(RedirectActivity::class.java.name), found)
    }
}
