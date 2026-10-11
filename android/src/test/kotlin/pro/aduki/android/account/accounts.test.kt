package pro.aduki.android.account

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.aduki.android.softVault
import pro.aduki.core.models.Linked
import pro.aduki.core.models.Switcher
import pro.aduki.core.models.Tokens

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountsTest {
    private lateinit var context: Context
    private lateinit var manager: AccountManager
    private lateinit var accounts: Accounts
    private val vault = softVault()
    private val ada = Linked("a1b2", "ada@aduki.me", true, null, "center")
    private val work = Linked("c3d4", "ada@adalabs.dev", false, "t1", "self")
    private val contoso = Linked("e5f6", "ada@contoso.com", false, "t2", "self")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = AccountManager.get(context)
        accounts = Accounts(context, vault, manager)
    }

    private fun center(vararg list: Linked, id: String = "c1", token: String = "center-token") =
        Switcher(id, token, 4_000_000_000L, list.toList())

    @Test fun reconcileAddsAccountsUnderTheOneType() {
        val change = accounts.reconcile(center(ada, work, contoso))
        assertEquals(listOf("a1b2", "c3d4", "e5f6"), change.added)
        assertEquals(3, manager.getAccountsByType("pro.aduki.account").size)
        val list = accounts.list()
        assertEquals("personal first, then by handle", listOf("ada@aduki.me", "ada@adalabs.dev", "ada@contoso.com"), list.map { it.handle })
        assertEquals(listOf("center", "self", "self"), list.map { it.unlock })
        assertEquals(setOf("c1"), list.map { it.center }.toSet())
        assertEquals("t1", list[1].tenant)
    }

    @Test fun reconcileIsIdempotentAndKeepsTokens() {
        accounts.reconcile(center(ada, work))
        accounts.save("a1b2", Tokens("acc", "ref", "600", "s1"))
        val again = accounts.reconcile(center(ada, work))
        assertTrue(again.added.isEmpty() && again.removed.isEmpty() && again.updated.isEmpty())
        assertEquals("ref", accounts.tokens("a1b2")!!.refresh)
    }

    @Test fun anAccountThePolicyUnlinksIsRemovedWithItsTokens() {
        accounts.reconcile(center(ada, contoso))
        accounts.save("e5f6", Tokens("acc", "ref", "600", "s"))
        val change = accounts.reconcile(center(ada))
        assertEquals(listOf("e5f6"), change.removed)
        assertNull(accounts.find("e5f6"))
        assertNull(accounts.tokens("e5f6"))
        assertEquals(1, manager.getAccountsByType("pro.aduki.account").size)
    }

    @Test fun otherCentersAreLeftAlone() {
        accounts.reconcile(center(ada, id = "c1"))
        accounts.reconcile(center(work, id = "c2"))
        assertEquals(2, accounts.list().size)
        accounts.reconcile(center(id = "c2"))
        assertEquals(listOf("a1b2"), accounts.list().map { it.hex })
    }

    @Test fun unlockPolicyChangeUpdatesTheEntry() {
        accounts.reconcile(center(ada, work))
        val change = accounts.reconcile(center(ada, work.copy(unlock = "center")))
        assertEquals(listOf("c3d4"), change.updated)
        assertEquals("center", accounts.find("c3d4")!!.unlock)
    }

    @Test fun tokensAreSealedInTheSystemStoreAndRoundTrip() {
        accounts.reconcile(center(ada))
        accounts.save("a1b2", Tokens("adka_secret_access", "adkr_secret_refresh", "600", "s1"))
        val entry = accounts.find("a1b2")!!
        val storedPassword = manager.getPassword(entry.account)
        val storedAccess = manager.peekAuthToken(entry.account, "access")
        assertNotEquals("adkr_secret_refresh", storedPassword)
        assertNotEquals("adka_secret_access", storedAccess)
        assertTrue(storedPassword!!.startsWith("aduki:1:") && storedAccess!!.startsWith("aduki:1:"))
        assertEquals(Tokens("adka_secret_access", "adkr_secret_refresh", "600", "s1"), accounts.tokens("a1b2"))
    }

    @Test fun theCenterTokenIsNeverInAnAccountEntryOrAnAccessToken() {
        accounts.reconcile(center(ada, work, token = "CENTER-TOKEN-VALUE"))
        accounts.save("a1b2", Tokens("adka_x", "adkr_x", "600", "s1"))
        val entry = accounts.find("a1b2")!!
        for (account in manager.getAccountsByType("pro.aduki.account")) {
            for (key in listOf("hex", "center", "personal", "tenant", "unlock", "expires", "session", "saved")) {
                assertFalse(manager.getUserData(account, key).orEmpty().contains("CENTER-TOKEN-VALUE"))
            }
            assertFalse(manager.getPassword(account).orEmpty().contains("CENTER-TOKEN-VALUE"))
        }
        assertFalse(accounts.tokens(entry.hex)!!.token.contains("CENTER-TOKEN-VALUE"))
        assertEquals("CENTER-TOKEN-VALUE", accounts.centerToken(now = 1))
        assertNull("expired center token is not returned", accounts.centerToken(now = 4_000_000_001L))
        val raw = context.getSharedPreferences("pro.aduki.center", Context.MODE_PRIVATE).getString("token", "")!!
        assertFalse("sealed at rest", raw.contains("CENTER-TOKEN-VALUE"))
    }

    @Test fun freshnessDrivesInstantSwitching() {
        accounts.reconcile(center(ada, work))
        accounts.save("a1b2", Tokens("acc", "ref", "600", "s"), now = 1_000_000)
        assertTrue(accounts.fresh("a1b2", now = 1_000_000 + 500_000))
        assertFalse("inside the 30 s skew", accounts.fresh("a1b2", now = 1_000_000 + 575_000))
        assertFalse(accounts.fresh("a1b2", now = 1_000_000 + 700_000))
        assertFalse("no tokens saved", accounts.fresh("c3d4"))
        assertTrue("signed out account asks to sign in again", accounts.needsSignIn("c3d4"))
        assertFalse(accounts.needsSignIn("a1b2"))
    }

    @Test fun switcherStateShowsLocksSignInAgainAndRemovals() {
        accounts.reconcile(center(ada, work))
        accounts.save("a1b2", Tokens("acc", "ref", "600", "s"), now = 1_000_000)
        val state = SwitcherState.of(accounts, active = "a1b2", removed = listOf("ada@contoso.com"), now = 1_100_000)
        val personal = state.rows[0]
        assertTrue(personal.active && personal.personal && !personal.locked && personal.instant && !personal.signInAgain)
        val member = state.rows[1]
        assertTrue(member.locked && member.signInAgain && !member.instant && !member.active)
        assertEquals(listOf("ada@contoso.com"), state.removed)
    }

    @Test fun refreshWithNoCenterKeepsTheSignedInAccountAndDropsLinkedOnes() {
        accounts.reconcile(center(ada, work))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"kind":"entity.missing","message":"none"}"""))
            val change = accounts.refresh(OkHttpClient(), server.url("/v1").toString(), "bearer", current = "a1b2")
            assertEquals(listOf("c3d4"), change.removed)
        }
        assertEquals(listOf("a1b2"), accounts.list().map { it.hex })
        assertEquals("", accounts.find("a1b2")!!.center)
    }

    @Test fun aTenantLinksDenyRemovesTheMemberWithinOneSecond() {
        accounts.reconcile(center(ada, work, contoso))
        accounts.save("e5f6", Tokens("acc", "ref", "600", "s"))
        // the id server now answers with the center without the denied tenant's member
        val body = centerBody(ada, work)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val started = System.nanoTime()
            val change = accounts.refresh(OkHttpClient(), server.url("/v1").toString(), "bearer", current = "a1b2")
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("e5f6"), change.removed)
            assertTrue("removal took $ms ms", ms < 1000)
        }
        assertNull(accounts.find("e5f6"))
        assertNull(accounts.tokens("e5f6"))
    }

    private fun centerBody(vararg list: Linked): String {
        val acc = list.joinToString(",", "[", "]") {
            """{"hex":"${it.hex}","handle":"${it.handle}","knd":"${if (it.personal) "p" else "m"}",${it.tenant?.let { t -> """"ten":"$t",""" } ?: ""}"unlock":"${it.unlock}"}"""
        }
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
        val token = "h." + b64.encodeToString("""{"acc":$acc}""".toByteArray()) + ".s"
        return """{"data":{"center":"c1","token":"$token","expires":4000000000}}"""
    }

    @After fun clear() = accounts.clear()
}
