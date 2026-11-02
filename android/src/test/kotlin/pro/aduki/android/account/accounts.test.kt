package pro.aduki.android.account

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.aduki.android.softVault
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Linked
import pro.aduki.core.models.Switcher
import pro.aduki.core.models.Tokens
import pro.aduki.crypto.dpop.Software

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
        accounts = Accounts(context, Accounts.Mode.Own(vault), manager)
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

    @Test fun ownTokensAreSealedInThePrivateStoreOnlyAndRoundTrip() {
        accounts.reconcile(center(ada))
        accounts.save("a1b2", Tokens("adka_secret_access", "adkr_secret_refresh", "600", "s1"))
        val entry = accounts.find("a1b2")!!
        assertNull("no copy in the shared entry", manager.getPassword(entry.account))
        assertNull(manager.peekAuthToken(entry.account, "access"))
        val raw = context.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE).getString("a1b2", "")!!
        assertFalse("sealed at rest", raw.contains("adkr_secret_refresh") || raw.contains("adka_secret_access"))
        assertEquals(Tokens("adka_secret_access", "adkr_secret_refresh", "600", "s1"), accounts.tokens("a1b2"))
        assertTrue(accounts.holds("a1b2", context.packageName))
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

    @Test fun shareSetsAccountVisibilityOnAndroid8Plus() {
        accounts.reconcile(center(ada, work))
        val shared = accounts.share(listOf("pro.aduki.suite.mail", "pro.aduki.suite.calendar"))
        assertEquals(2, shared)

        assertEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("a1b2", "pro.aduki.suite.mail"))
        assertEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("a1b2", "pro.aduki.suite.calendar"))
        assertEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("c3d4", "pro.aduki.suite.mail"))

        // Share single account by hex
        assertTrue(accounts.share("a1b2", listOf("pro.aduki.suite.drive")))
        assertEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("a1b2", "pro.aduki.suite.drive"))
        assertNotEquals(AccountManager.VISIBILITY_VISIBLE, accounts.visibility("c3d4", "pro.aduki.suite.drive"))
    }

    @Test fun sharedModeKeepsOneCopyInTheEntryAndEveryAppReadsIt() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Shared, manager)
        val calendar = Accounts(AppContext(context, "pro.aduki.calendar"), Accounts.Mode.Shared, manager)
        mail.reconcile(center(ada))
        mail.save("a1b2", Tokens("access_1", "refresh_1", "600", "sess_1"))
        assertEquals("refresh_1", calendar.tokens("a1b2")?.refresh)

        // Mail renews: Calendar's next read is the rotated pair, never a stale copy (aduki-kt#118).
        mail.save("a1b2", Tokens("access_2", "refresh_2", "600", "sess_1"))
        assertEquals(Tokens("access_2", "refresh_2", "600", "sess_1"), calendar.tokens("a1b2"))
    }

    @Test fun sharedModeDropsAStalePrivateCopyAnOlderSdkLeft() {
        val calContext = AppContext(context, "pro.aduki.calendar")
        val calendar = Accounts(calContext, Accounts.Mode.Shared, manager)
        calendar.reconcile(center(ada))
        calendar.save("a1b2", Tokens("fresh_access", "fresh_refresh", "600", "s"))
        // what 0.5.4 left in the app's own preferences
        calContext.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE).edit()
            .putString("a1b2", JSONObject().put("token", "old").put("refresh", "spent").toString()).commit()
        assertEquals("fresh_refresh", calendar.tokens("a1b2")?.refresh)
        assertNull(calContext.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE).getString("a1b2", null))
    }

    @Test fun readTellsMissingFromSealed() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        mail.reconcile(center(ada))
        assertEquals(Accounts.Stored.Missing, mail.read("a1b2"))
        mail.save("a1b2", Tokens("a", "r", "600", "s"))
        assertTrue(mail.read("a1b2") is Accounts.Stored.Found)

        // the Keystore key is gone (or the file came from another app): sealed, not "signed out"
        val rekeyed = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        assertEquals(Accounts.Stored.Sealed, rekeyed.read("a1b2"))
        assertNull(rekeyed.tokens("a1b2"))

        // a shared entry holding sealed text cannot be used by a Shared reader
        val entry = mail.find("a1b2")!!
        manager.setPassword(entry.account, softVault().sealText("r"))
        assertEquals(Accounts.Stored.Sealed, Accounts(context, Accounts.Mode.Shared, manager).read("a1b2"))
    }

    @Test fun ownModeNeverAdoptsTheSharedEntry() {
        val shared = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Shared, manager)
        shared.reconcile(center(ada))
        shared.save("a1b2", Tokens("plain_access", "plain_refresh", "600", "s"))
        val own = Accounts(AppContext(context, "pro.aduki.calendar"), Accounts.Mode.Own(softVault()), manager)
        assertEquals(Accounts.Stored.Missing, own.read("a1b2"))
        assertEquals("unsealed tokens of another app are not adopted (aduki-kt#113)", Accounts.Stored.Sealed, own.migrate("a1b2"))
        assertEquals(Accounts.Stored.Missing, own.read("a1b2"))
    }

    @Test fun migrateMovesThisAppsOldSealedEntryIntoThePrivateStore() {
        val mailVault = softVault()
        val mailContext = AppContext(context, "pro.aduki.mail")
        val mail = Accounts(mailContext, Accounts.Mode.Own(mailVault), manager)
        mail.reconcile(center(ada))
        val entry = mail.find("a1b2")!!
        // what 0.5.3 wrote: sealed with this app's vault, in the entry
        manager.setPassword(entry.account, mailVault.sealText("old_refresh"))
        manager.setAuthToken(entry.account, "access", mailVault.sealText("old_access"))
        manager.setUserData(entry.account, "expires", "600")
        assertEquals(Accounts.Stored.Missing, mail.read("a1b2"))
        val moved = mail.migrate("a1b2") as Accounts.Stored.Found
        assertEquals("old_refresh", moved.tokens.refresh)
        assertNull("the entry no longer holds it", manager.getPassword(entry.account))
    }

    @Test fun aHandoffIsOfferedToOnePeerAndClaimedOnce() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        val calendar = Accounts(AppContext(context, "pro.aduki.calendar"), Accounts.Mode.Own(softVault()), manager)
        val contacts = Accounts(AppContext(context, "pro.aduki.contacts"), Accounts.Mode.Own(softVault()), manager)
        mail.reconcile(center(ada))
        mail.save("a1b2", Tokens("mail_access", "mail_refresh", "600", "s"))
        assertFalse(mail.holds("a1b2", "pro.aduki.calendar"))
        val later = System.currentTimeMillis() / 1000 + 3600
        assertTrue(mail.offer("a1b2", "pro.aduki.calendar", pro.aduki.net.http.Handoff("adkh_cal", later)))
        assertFalse("not to itself", mail.offer("a1b2", "pro.aduki.mail", pro.aduki.net.http.Handoff("adkh_x", later)))

        // nothing of Mail's session is readable by the peers
        assertNull(calendar.tokens("a1b2"))
        assertNull(contacts.claim("a1b2"))
        assertEquals("adkh_cal", calendar.claim("a1b2"))
        assertNull("single use", calendar.claim("a1b2"))
        for (key in listOf("handoff.pro.aduki.calendar", "handoff.pro.aduki.contacts")) {
            assertNull(manager.getUserData(mail.find("a1b2")!!.account, key))
        }

        // Calendar redeems it at Aduki ID with its own key and saves its own session
        calendar.save("a1b2", Tokens("cal_access", "cal_refresh", "600", "s2"))
        assertTrue(mail.holds("a1b2", "pro.aduki.calendar"))
        assertEquals("mail_refresh", mail.tokens("a1b2")?.refresh)
    }

    @Test fun expiredOrWithdrawnHandoffsAreNotClaimed() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        val calendar = Accounts(AppContext(context, "pro.aduki.calendar"), Accounts.Mode.Own(softVault()), manager)
        mail.reconcile(center(ada))
        val now = System.currentTimeMillis() / 1000
        assertFalse("already expired", mail.offer("a1b2", "pro.aduki.calendar", pro.aduki.net.http.Handoff("adkh_old", now - 1)))
        assertTrue(mail.offer("a1b2", "pro.aduki.calendar", pro.aduki.net.http.Handoff("adkh_1", now + 60)))
        assertNull("expired by the time it is claimed", calendar.claim("a1b2", now = now + 61))
        assertTrue(mail.offer("a1b2", "pro.aduki.calendar", pro.aduki.net.http.Handoff("adkh_2", now + 60)))
        assertTrue(mail.withdraw("a1b2", "pro.aduki.calendar"))
        assertNull(calendar.claim("a1b2"))
    }

    @Test fun shareGrantsVisibilityAndHandsOverNoTokens() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        mail.reconcile(center(ada))
        mail.save("a1b2", Tokens("acc", "ref", "600", "s"))
        mail.share(listOf("pro.aduki.calendar"))
        val calendar = Accounts(AppContext(context, "pro.aduki.calendar"), Accounts.Mode.Own(softVault()), manager)
        assertEquals("a1b2", calendar.list().single().hex)
        assertEquals(Accounts.Stored.Missing, calendar.read("a1b2"))
        assertNull(calendar.claim("a1b2"))
    }

    @Test fun forgetEndsThisAppsSessionAndKeepsTheEntry() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        mail.reconcile(center(ada))
        mail.save("a1b2", Tokens("acc", "ref", "600", "s"))
        mail.forget("a1b2")
        assertEquals(Accounts.Stored.Missing, mail.read("a1b2"))
        assertFalse(mail.holds("a1b2", "pro.aduki.mail"))
        assertEquals("a1b2", mail.find("a1b2")?.hex)

        val shared = Accounts(context, Accounts.Mode.Shared, manager)
        shared.save("a1b2", Tokens("acc", "ref", "600", "s"))
        shared.forget("a1b2")
        assertEquals(Accounts.Stored.Missing, shared.read("a1b2"))
    }

    @Test fun aSharedInstanceLeavesAnOwnAppsSessionAlone() {
        val ctx = AppContext(context, "pro.aduki.mail")
        val own = Accounts(ctx, Accounts.Mode.Own(softVault()), manager)
        own.reconcile(center(ada))
        own.save("a1b2", Tokens("own_access", "own_refresh", "600", "s"))
        val shared = Accounts(ctx, Accounts.Mode.Shared, manager)
        shared.read("a1b2")
        shared.save("a1b2", Tokens("shared_access", "shared_refresh", "600", "s"))
        shared.forget("a1b2")
        assertEquals("aduki-kt#122", "own_refresh", own.tokens("a1b2")?.refresh)
        // an unmarked, unsealed 0.5.x copy is still dropped
        ctx.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE).edit()
            .putString("e5f6", JSONObject().put("token", "old").put("refresh", "spent").toString()).commit()
        own.reconcile(center(ada, contoso))
        shared.read("e5f6")
        assertNull(ctx.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE).getString("e5f6", null))
    }

    @Test fun anOfferedHandoffCanBeReadWithoutClaimingIt() {
        val mail = Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager)
        val calendar = Accounts(AppContext(context, "pro.aduki.calendar"), Accounts.Mode.Own(softVault()), manager)
        mail.reconcile(center(ada))
        val now = System.currentTimeMillis() / 1000
        assertNull(mail.offered("a1b2", "pro.aduki.calendar"))
        mail.offer("a1b2", "pro.aduki.calendar", pro.aduki.net.http.Handoff("adkh_1", now + 600))
        assertEquals(pro.aduki.net.http.Handoff("adkh_1", now + 600), mail.offered("a1b2", "pro.aduki.calendar"))
        assertNull("expired", mail.offered("a1b2", "pro.aduki.calendar", now = now + 601))
        assertEquals("still there to claim", "adkh_1", calendar.claim("a1b2"))
        assertNull(mail.offered("a1b2", "pro.aduki.calendar"))
    }

    @Test fun discardReturnsAndDropsThisAppsTokensAfterTheEntryIsGone() {
        val ctx = AppContext(context, "pro.aduki.calendar")
        val calendar = Accounts(ctx, Accounts.Mode.Own(softVault()), manager)
        calendar.reconcile(center(ada))
        calendar.save("a1b2", Tokens("cal_access", "cal_refresh", "600", "s9"))
        Accounts(AppContext(context, "pro.aduki.mail"), Accounts.Mode.Own(softVault()), manager).remove("a1b2")
        assertNull(calendar.find("a1b2"))
        assertEquals("aduki-kt#124", Tokens("cal_access", "cal_refresh", "600", "s9"), calendar.discard("a1b2"))
        assertNull("gone", calendar.discard("a1b2"))
        assertNull(ctx.getSharedPreferences("pro.aduki.account.tokens", Context.MODE_PRIVATE).getString("a1b2", null))
    }

    @Test fun customVisibilityProviderTestSeam() {
        val recorded = mutableListOf<Triple<String, String, Int>>()
        val customVisibility = object : Visibility {
            override fun set(account: android.accounts.Account, target: String, mode: Int): Boolean {
                recorded.add(Triple(account.name, target, mode))
                return true
            }
            override fun get(account: android.accounts.Account, target: String): Int =
                AccountManager.VISIBILITY_VISIBLE
        }

        val testAccounts = Accounts(context, Accounts.Mode.Own(vault), manager, visibility = customVisibility)
        testAccounts.reconcile(center(ada))
        testAccounts.share("a1b2", listOf("pro.aduki.calendar"))

        assertEquals(1, recorded.size)
        assertEquals("ada@aduki.me", recorded[0].first)
        assertEquals("pro.aduki.calendar", recorded[0].second)
        assertEquals(AccountManager.VISIBILITY_VISIBLE, recorded[0].third)
        assertEquals(AccountManager.VISIBILITY_VISIBLE, testAccounts.visibility("a1b2", "pro.aduki.calendar"))
    }

    @Test fun signingOutFromAnyAppDropsAccountForPeerApps() {
        val mailContext = AppContext(context, "pro.aduki.mail")
        val calContext = AppContext(context, "pro.aduki.calendar")

        val mailAccounts = Accounts(mailContext, Accounts.Mode.Shared, manager)
        mailAccounts.reconcile(center(ada))
        mailAccounts.save("a1b2", Tokens("acc", "ref", "600", "s"))
        mailAccounts.share(listOf("pro.aduki.calendar"))

        val calAccounts = Accounts(calContext, Accounts.Mode.Shared, manager)
        assertEquals("acc", calAccounts.tokens("a1b2")?.token)

        // Sign out from Calendar app
        calAccounts.remove("a1b2")

        // Account is removed from AccountManager, gone for both apps
        assertNull(mailAccounts.find("a1b2"))
        assertNull(calAccounts.find("a1b2"))
        assertTrue(mailAccounts.list().isEmpty())
        assertTrue(calAccounts.list().isEmpty())
    }

    @Test fun registerDeviceStoresCredential() {
        val key = Software.p256()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"cred_dev_1","trust_days":60,"alg":"ES256"}}"""))
            val cred = accounts.registerDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok", "Pixel 8", "123456", center = true, key = key)
            assertEquals("cred_dev_1", cred)
            assertEquals("cred_dev_1", accounts.credential())

            val req = server.takeRequest()
            assertEquals("/v1/credentials", req.path)
            assertEquals("Bearer bearer_tok", req.getHeader("Authorization"))
            val body = JSONObject(req.body.readUtf8())
            assertEquals("Pixel 8", body.getString("device"))
            assertEquals("ES256", body.getString("alg"))
            assertEquals("123456", body.getString("code"))
            assertTrue(body.getBoolean("center"))
            val pub = java.util.Base64.getUrlDecoder().decode(body.getString("public"))
            assertEquals(65, pub.size)
            assertEquals(0x04.toByte(), pub[0])
        }
    }

    @Test fun unlockWithDeviceSignsChallengeAndSavesTokens() {
        val key = Software.p256()
        accounts.reconcile(center(ada, work))

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"cred_dev_1","trust_days":60,"alg":"ES256"}}"""))
            accounts.registerDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok", "Pixel 8", "123456", center = true, key = key)

            val nonce = ByteArray(32) { (it + 7).toByte() }
            val b64Nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"nonce":"$b64Nonce","expires":"2026-10-11T12:02:00Z"}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[
                {"account":"a1b2","session":"s1","access":"adka_a1","refresh":"adkr_a1","expires":600},
                {"account":"c3d4","session":"s2","access":"adka_c3","refresh":"adkr_c3","expires":600}
            ]}"""))

            val unlocked = accounts.unlockWithDevice(OkHttpClient(), server.url("/v1").toString(), audience = "space", key = key)
            assertEquals(listOf("a1b2", "c3d4"), unlocked)
            assertEquals("adka_a1", accounts.tokens("a1b2")?.token)
            assertEquals("adka_c3", accounts.tokens("c3d4")?.token)

            // take registration request
            server.takeRequest()

            val challengeReq = server.takeRequest()
            assertEquals("/v1/credentials/cred_dev_1/challenges", challengeReq.path)
            assertNull(challengeReq.getHeader("Authorization"))

            val unlockReq = server.takeRequest()
            assertEquals("/v1/credentials/cred_dev_1/unlock", unlockReq.path)
            val unlockBody = JSONObject(unlockReq.body.readUtf8())
            assertEquals("space", unlockBody.getString("audience"))
            assertEquals(b64Nonce, unlockBody.getString("nonce"))
            val sig = java.util.Base64.getUrlDecoder().decode(unlockBody.getString("signature"))
            assertEquals(64, sig.size)
        }
    }

    @Test fun unlockWithDeviceWithoutCredentialThrowsIllegalStateException() {
        val key = Software.p256()
        try {
            accounts.unlockWithDevice(OkHttpClient(), "http://localhost", key = key)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No device credential registered", e.message)
        }
    }

    @Test fun unlockWithDevice404ClearsCredentialAndThrowsNetworkException() {
        val key = Software.p256()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"cred_dev_1","trust_days":60,"alg":"ES256"}}"""))
            accounts.registerDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok", "Pixel 8", "123456", center = true, key = key)
            assertEquals("cred_dev_1", accounts.credential())

            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"status":404,"kind":"entity.missing","message":"credential revoked or lapsed"}}"""))
            try {
                accounts.unlockWithDevice(OkHttpClient(), server.url("/v1").toString(), key = key)
                fail("Expected 404 AdukiException.Network")
            } catch (e: AdukiException.Network) {
                assertEquals(404, e.code)
            }
            assertNull(accounts.credential())
        }
    }

    @Test fun unlockWithDevice401DoesNotClearCredential() {
        val key = Software.p256()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"cred_dev_1","trust_days":60,"alg":"ES256"}}"""))
            accounts.registerDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok", "Pixel 8", "123456", center = true, key = key)
            assertEquals("cred_dev_1", accounts.credential())

            val nonce = ByteArray(32) { (it + 1).toByte() }
            val b64Nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"nonce":"$b64Nonce"}}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"bad signature"}}"""))

            try {
                accounts.unlockWithDevice(OkHttpClient(), server.url("/v1").toString(), key = key)
                fail("Expected 401 AdukiException.Unauthorized")
            } catch (_: AdukiException.Unauthorized) {
            }
            assertEquals("cred_dev_1", accounts.credential())
        }
    }

    @Test fun revokeDeviceDeletesCredentialAndClearsLocal() {
        val key = Software.p256()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"cred_dev_1","trust_days":60,"alg":"ES256"}}"""))
            accounts.registerDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok", "Pixel 8", "123456", center = true, key = key)
            assertEquals("cred_dev_1", accounts.credential())

            server.enqueue(MockResponse().setResponseCode(204))
            val ok = accounts.revokeDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok")
            assertTrue(ok)
            assertNull(accounts.credential())

            // registration request
            server.takeRequest()
            val revokeReq = server.takeRequest()
            assertEquals("DELETE", revokeReq.method)
            assertEquals("/v1/credentials/cred_dev_1", revokeReq.path)
            assertEquals("Bearer bearer_tok", revokeReq.getHeader("Authorization"))
        }
    }

    @Test fun revokeDeviceWhenAlready404ReturnsFalseAndClearsLocal() {
        val key = Software.p256()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"cred_dev_1","trust_days":60,"alg":"ES256"}}"""))
            accounts.registerDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok", "Pixel 8", "123456", center = true, key = key)

            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"status":404,"kind":"entity.missing","message":"none"}}"""))
            val ok = accounts.revokeDevice(OkHttpClient(), server.url("/v1").toString(), "bearer_tok")
            assertFalse(ok)
            assertNull(accounts.credential())
        }
    }

    @Test fun revokeDeviceWhenNoCredentialReturnsFalse() {
        assertNull(accounts.credential())
        val ok = accounts.revokeDevice(OkHttpClient(), "http://localhost", "bearer_tok")
        assertFalse(ok)
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

private class AppContext(base: Context, private val pkg: String) : android.content.ContextWrapper(base) {
    override fun getPackageName(): String = pkg
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        baseContext.getSharedPreferences("${pkg}_$name", mode)
    override fun getApplicationContext(): Context = this
}
