package pro.aduki.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.aduki.android.softVault
import pro.aduki.core.models.AccountProfile
import pro.aduki.core.models.Tenancy
import pro.aduki.core.models.Tokens

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartnersTest {
    private lateinit var context: Context
    private lateinit var manager: AccountManager
    private val lavernai = "com.lavernai.triviaconnect"
    private val ada = AccountProfile("a1b2", "ada@aduki.me", name = "Ada Lovelace", photo = "ph1", session = "sess_secret", group = "grp_secret")
    private val work = AccountProfile("c3d4", "ada@adalabs.dev", kind = AccountProfile.Kind.Member, tenant = Tenancy("t1", "Ada Labs"))

    // Each caller uid stands for one kind of app.
    private val callers: (Int) -> Caller = { uid ->
        when (uid) {
            SIBLING -> Caller.Sibling
            PARTNER -> Caller.Partner
            else -> Caller.Other
        }
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = AccountManager.get(context)
        Partners.packages = Partners.DEFAULT
        Authenticator.refresher = null
    }

    @After fun reset() {
        Partners.packages = Partners.DEFAULT
    }

    private fun from(uid: Int) = Bundle().apply { putInt(AccountManager.KEY_CALLER_UID, uid) }

    @Test fun lavernaiIsTheDefaultPartner() {
        assertEquals(listOf(lavernai), Partners.DEFAULT)
        assertEquals(listOf("pro.aduki.calendar", lavernai), Partners.with(listOf("pro.aduki.calendar", lavernai)))
    }

    @Test fun shareAlsoGrantsThePartners() {
        val accounts = Accounts(context, Accounts.Mode.Own(softVault()), manager)
        AccountDirectory(context, manager = manager).upsert(ada)
        assertEquals(1, accounts.share(listOf("pro.aduki.calendar")))
        assertEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("a1b2", "pro.aduki.calendar"))
        assertEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("a1b2", lavernai))
        assertEquals("partners alone are shared too", 1, accounts.share(emptyList()))
    }

    @Test fun newDirectoryEntriesAreVisibleToThePartners() {
        val seen = HashMap<String, Int>()
        val visibility = object : Visibility {
            override fun set(account: Account, target: String, mode: Int): Boolean { seen[target] = mode; return true }
            override fun get(account: Account, target: String): Int = seen[target] ?: AccountManager.VISIBILITY_NOT_VISIBLE
        }
        AccountDirectory(context, listOf("pro.aduki.mail"), manager, visibility).upsert(ada)
        assertEquals(AccountManager.VISIBILITY_VISIBLE, seen["pro.aduki.mail"])
        assertEquals(AccountManager.VISIBILITY_VISIBLE, seen[lavernai])
    }

    @Test fun anEmptyPartnerListSharesWithNobodyElse() {
        Partners.packages = emptyList()
        val accounts = Accounts(context, Accounts.Mode.Own(softVault()), manager)
        AccountDirectory(context, manager = manager).upsert(ada)
        assertEquals(0, accounts.share(emptyList()))
        accounts.share(listOf("pro.aduki.calendar"))
        assertTrue(accounts.visibility("a1b2", lavernai) != AccountManager.VISIBILITY_VISIBLE)
    }

    @Test fun aPartnerGetsThePublicProfileAndNoTokenInSharedMode() {
        val accounts = Accounts(context, Accounts.Mode.Shared, manager)
        AccountDirectory(context, manager = manager).upsert(ada)
        accounts.save("a1b2", Tokens("adka_secret_access", "adkr_secret_refresh", "600", "sess_secret"))
        val authenticator = AdukiAuthenticator(context, accounts, callers)
        val account = accounts.find("a1b2")!!.account

        val result = authenticator.getAuthToken(null, account, PartnerAccounts.PROFILE, from(PARTNER))
        val json = result.getString(PartnerAccounts.KEY_PROFILE)!!
        assertEquals(json, result.getString(AccountManager.KEY_AUTHTOKEN))
        assertNoSecret(result)
        val profile = JSONObject(json)
        assertEquals(setOf("hex", "handle", "name", "personal", "photo"), profile.keys().asSequence().toSet())
        assertEquals("a1b2", profile.getString("hex"))
        assertEquals("Ada Lovelace", profile.getString("name"))

        // An access token is refused to a partner, cached or not.
        val access = authenticator.getAuthToken(null, account, Accounts.ACCESS, from(PARTNER))
        assertNull(access.getString(AccountManager.KEY_AUTHTOKEN))
        assertNull(access.getParcelable<android.content.Intent>(AccountManager.KEY_INTENT))
        assertEquals(AccountManager.ERROR_CODE_BAD_REQUEST, access.getInt(AccountManager.KEY_ERROR_CODE))
        assertNoSecret(access)
    }

    @Test fun aPartnerGetsNoTokenInOwnModeEither() {
        val accounts = Accounts(context, Accounts.Mode.Own(softVault()), manager)
        AccountDirectory(context, manager = manager).upsert(ada)
        accounts.save("a1b2", Tokens("adka_secret_access", "adkr_secret_refresh", "600", "sess_secret"))
        val authenticator = AdukiAuthenticator(context, accounts, callers)
        val account = accounts.find("a1b2")!!.account
        assertNoSecret(authenticator.getAuthToken(null, account, PartnerAccounts.PROFILE, from(PARTNER)))
        val access = authenticator.getAuthToken(null, account, Accounts.ACCESS, from(PARTNER))
        assertEquals(AccountManager.ERROR_CODE_BAD_REQUEST, access.getInt(AccountManager.KEY_ERROR_CODE))
        assertNoSecret(access)
        // The sibling path is unchanged.
        assertEquals("adka_secret_access", authenticator.getAuthToken(null, account, Accounts.ACCESS, from(SIBLING)).getString(AccountManager.KEY_AUTHTOKEN))
    }

    @Test fun anyOtherAppGetsNothing() {
        val accounts = Accounts(context, Accounts.Mode.Shared, manager)
        AccountDirectory(context, manager = manager).upsert(ada)
        accounts.save("a1b2", Tokens("adka_secret_access", "adkr_secret_refresh", "600", "sess_secret"))
        val authenticator = AdukiAuthenticator(context, accounts, callers)
        val account = accounts.find("a1b2")!!.account
        for (type in listOf(PartnerAccounts.PROFILE, Accounts.ACCESS)) {
            val result = authenticator.getAuthToken(null, account, type, from(OTHER))
            assertEquals(AccountManager.ERROR_CODE_BAD_REQUEST, result.getInt(AccountManager.KEY_ERROR_CODE))
            assertNull(result.getString(PartnerAccounts.KEY_PROFILE))
            assertNoSecret(result)
        }
    }

    @Test fun aMemberProfileCarriesTheOrganisationName() {
        val json = PartnerAccounts.profile(DeviceAccount(work, linked = true))
        val parsed = PartnerAccounts.parse(json, "x")!!
        assertEquals(PartnerAccount("c3d4", "ada@adalabs.dev", personal = false, tenant = "Ada Labs"), parsed)
        assertFalse(json.contains("t1"))
    }

    @Test fun listReadsUserDataWhenAllowedAndAsksTheAuthenticatorOtherwise() {
        val a = Account("ada@aduki.me", Accounts.TYPE)
        val b = Account("ada@adalabs.dev", Accounts.TYPE)
        val c = Account("bob@aduki.me", Accounts.TYPE)
        val sibling = object : PartnerAccounts.Source {
            override fun accounts() = listOf(b, a)
            override fun data(account: Account, key: String): String? = mapOf(
                "hex" to if (account == a) "a1b2" else "c3d4",
                "name" to if (account == a) "Ada" else "",
                "personal" to (account == a).toString(),
                "tenantName" to if (account == a) "" else "Ada Labs"
            )[key]
            override fun profile(account: Account): String? = error("a sibling reads the entry")
        }
        assertEquals(
            listOf(PartnerAccount("a1b2", "ada@aduki.me", "Ada"), PartnerAccount("c3d4", "ada@adalabs.dev", personal = false, tenant = "Ada Labs")),
            PartnerAccounts.list(sibling)
        )

        val partner = object : PartnerAccounts.Source {
            override fun accounts() = listOf(a, c)
            override fun data(account: Account, key: String): String? = throw SecurityException("uid cannot get user data")
            override fun profile(account: Account): String? =
                if (account == a) PartnerAccounts.profile(DeviceAccount(ada, linked = false)) else throw java.io.IOException("timeout")
        }
        val listed = PartnerAccounts.list(partner)
        assertEquals(PartnerAccount("a1b2", "ada@aduki.me", "Ada Lovelace", photo = "ph1"), listed[0])
        assertEquals("handle only when the authenticator does not answer", PartnerAccount(null, "bob@aduki.me"), listed[1])
        assertEquals("Ada Lovelace", listed[0].label)
        assertEquals("bob@aduki.me", listed[1].label)
    }

    private fun assertNoSecret(bundle: Bundle) {
        val text = bundle.keySet().joinToString { "$it=${bundle.get(it)}" }
        for (secret in listOf("adka_secret_access", "adkr_secret_refresh", "sess_secret", "grp_secret")) {
            assertFalse("$secret leaked: $text", text.contains(secret))
        }
    }

    private companion object {
        const val SIBLING = 10_001
        const val PARTNER = 10_002
        const val OTHER = 10_003
    }
}
