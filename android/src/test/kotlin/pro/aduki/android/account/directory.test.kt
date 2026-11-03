package pro.aduki.android.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.aduki.android.softVault
import pro.aduki.core.models.AccountProfile
import pro.aduki.core.models.CenterView
import pro.aduki.core.models.Tenancy
import pro.aduki.core.models.TokenStore
import pro.aduki.core.models.Tokens
import pro.aduki.sdk.Aduki
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device directory (migration from email-keyed entries, reconcile that
 * never drops), the synchronous token save, one rotation per session in one
 * process, and the Accounts Center's add and remove flows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryTest {
    private lateinit var context: Context
    private lateinit var manager: AccountManager
    private lateinit var directory: AccountDirectory
    private val ada = AccountProfile("00a1", "ada@aduki.me", "Ada", AccountProfile.Kind.Personal, group = "g1")
    private val work = AccountProfile(
        "00b2", "ada@acme.example", "Ada A.", AccountProfile.Kind.Member,
        Tenancy("00t9", "Acme", "admin", false), photo = "e1", unlock = "self", links = "deny", group = "g2"
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = AccountManager.get(context)
        directory = AccountDirectory(context)
    }

    // --- migration -----------------------------------------------------------

    @Test
    fun anEmailKeyedEntryMovesToTheRealHexInPlace() {
        // What the suite wrote before 0.8.0: hex = email, a private token copy keyed by email.
        val legacy = Account("ada@aduki.me", Accounts.TYPE)
        manager.addAccountExplicitly(legacy, null, Bundle().apply {
            putString("hex", "ada@aduki.me"); putString("personal", "true"); putString("center", ""); putString("unlock", "self")
            putString("own.pro.aduki.mail", "1")
        })
        val accounts = Accounts(context, Accounts.Mode.Own(softVault()), manager)
        accounts.save("ada@aduki.me", Tokens("a", "r1", "600", "s1"))
        assertTrue(directory.needsMigration())

        val moved = directory.migrate(ada)
        assertEquals(AccountDirectory.Migration("ada@aduki.me", "00a1"), moved)
        assertFalse(directory.needsMigration())
        val entries = manager.getAccountsByType(Accounts.TYPE)
        assertEquals("updated in place, never recreated (no sign-out)", listOf(legacy), entries.toList())
        assertEquals("00a1", manager.getUserData(legacy, "hex"))
        assertEquals("Ada", manager.getUserData(legacy, "name"))
        val found = directory.find("00a1")!!
        assertEquals("g1", found.profile.group)
        assertEquals("the tokens moved with the entry", "r1", accounts.tokens("00a1")?.refresh)
        assertNull(accounts.tokens("ada@aduki.me"))
        assertNull("nothing left to migrate", directory.migrate(ada))
    }

    @Test
    fun theDirectoryKeepsTheWholeProfileAndAMirror() {
        directory.upsert(work, linked = false, refusal = Refusal.TenantDenies)
        directory.upsert(ada, linked = true)
        val list = directory.list()
        assertEquals("personal first", listOf("00a1", "00b2"), list.map { it.hex })
        val member = list[1]
        assertEquals("Acme", member.profile.tenant?.name)
        assertEquals("admin", member.profile.tenant?.role)
        assertEquals("e1", member.profile.photo)
        assertEquals(Refusal.TenantDenies, member.refusal)
        assertEquals("g2", member.profile.group)

        // Entries purged by the system come back from this app's mirror.
        manager.getAccountsByType(Accounts.TYPE).forEach { manager.removeAccountExplicitly(it) }
        assertEquals(setOf("00a1", "00b2"), directory.orphans().map { it.hex }.toSet())
        assertTrue(directory.restore("00b2"))
        assertEquals("Acme", directory.find("00b2")?.profile?.tenant?.name)
    }

    @Test
    fun reconcileNeverDropsADeviceOnlyAccount() {
        directory.upsert(ada.copy(center = "c1"), linked = true)
        directory.upsert(work.copy(center = "c1"), linked = true)
        directory.upsert(AccountProfile("00c3", "ada@other.example", kind = AccountProfile.Kind.Member), linked = false, refusal = Refusal.TenantDenies)

        // The tenant forced Acme's account out of the center.
        val change = directory.reconcile(CenterView("c1", "t", 9, listOf(ada)), "00a1")
        assertEquals(listOf("00b2"), change.unlinked)
        assertEquals(3, directory.list().size)
        val removed = directory.find("00b2")!!
        assertFalse(removed.linked)
        assertEquals("Acme", removed.removedBy)
        assertEquals(Refusal.TenantDenies, directory.find("00c3")!!.refusal)
    }

    // --- token save ------------------------------------------------------------

    /** Prefs that record how their edits were written. */
    private class Recorded(val base: SharedPreferences, val writes: MutableList<String>) : SharedPreferences by base {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by base.edit() {
            private val inner = base.edit()
            override fun putString(key: String?, value: String?) = apply { inner.putString(key, value) }
            override fun remove(key: String?) = apply { inner.remove(key) }
            override fun commit(): Boolean { writes += "commit"; return inner.commit() }
            override fun apply() { writes += "apply"; inner.apply() }
        }
    }

    @Test
    fun aRotatedTokenIsWrittenSynchronously() {
        val writes = mutableListOf<String>()
        val recording = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                return if (name == Accounts.TOKENS) Recorded(prefs, writes) else prefs
            }
        }
        val accounts = Accounts(recording, Accounts.Mode.Own(null), manager)
        directory.upsert(ada)
        assertTrue(accounts.save("00a1", Tokens("a", "rt_rotated", "600", "s1")))
        assertEquals("commit(), never apply(): the process may die right after", listOf("commit"), writes)
        val file = File(context.applicationInfo.dataDir, "shared_prefs/pro.aduki.account.tokens.xml")
        assertTrue(file.readText().contains("rt_rotated"))
    }

    // --- one rotation per session in one process --------------------------------

    /** The token store an app builds on [Accounts] (Mode.Own). */
    private class Stored(val accounts: Accounts, val hex: String) : TokenStore {
        override fun load(): Tokens? = accounts.tokens(hex)
        override fun save(tokens: Tokens) { accounts.save(hex, tokens) }
        override fun clear() = accounts.forget(hex)
    }

    @Test
    fun twoClientsSharingAnAccountRotateOnce() {
        val rotations = AtomicInteger()
        val presented = Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/id/tokens")) {
                    presented += JSONObject(request.body.readUtf8()).getString("refresh")
                    val n = rotations.incrementAndGet()
                    Thread.sleep(50)
                    return MockResponse().setResponseCode(201)
                        .setBody("""{"success":true,"data":{"access":"eyJ.new","refresh":"rt_${n + 1}","expires":600}}""")
                }
                if (request.getHeader("Authorization") != "Bearer eyJ.new") {
                    return MockResponse().setResponseCode(401).setBody("""{"success":false,"error":{"status":401,"kind":"auth.invalid","message":"m"}}""")
                }
                return MockResponse().setBody("""{"success":true,"data":[]}""")
            }
        }
        server.start()
        try {
            val accounts = Accounts(context, Accounts.Mode.Own(null), manager)
            directory.upsert(ada)
            accounts.save("00a1", Tokens("eyJ.old", "rt_1", "600", "s1"))
            fun client() = Aduki.builder().endpoint(server.url("/v1").toString()).identity(server.url("/id").toString())
                .tokens(Stored(accounts, "00a1")).build()
            // The sign-in client and a redeem client of one account in one process.
            val first = client()
            val second = client()
            val pool = Executors.newFixedThreadPool(6)
            val done = CountDownLatch(6)
            val failures = AtomicInteger()
            repeat(6) { i ->
                pool.execute {
                    try {
                        (if (i % 2 == 0) first else second).schedulingApi.services()
                    } catch (_: Exception) {
                        failures.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            pool.shutdown()
            assertEquals(0, failures.get())
            assertEquals(1, rotations.get())
            assertEquals(listOf("rt_1"), presented.toList())
            assertEquals("rt_2", accounts.tokens("00a1")?.refresh)
        } finally {
            server.shutdown()
        }
    }

    // --- Accounts Center ---------------------------------------------------------

    @Test
    fun addingAnAccountATenantForbidsKeepsItDeviceOnly() = runBlocking {
        val server = MockWebServer()
        val deletes = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val auth = request.getHeader("Authorization").orEmpty()
                return when {
                    path.startsWith("/id/tokens") -> {
                        val refresh = JSONObject(request.body.readUtf8()).getString("refresh")
                        MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"access":"id-$refresh","refresh":"$refresh","expires":600}}""")
                    }
                    path == "/id/me" && auth == "Bearer id-rA" -> MockResponse().setBody("""{"success":true,"data":{"hex":"00a1","handle":"ada@aduki.me",
                        "name":"Ada","kind":"personal","tenant":null,"photo":null,"center":null,"unlock":"self","session":"sA","group":"g1"}}""")
                    path == "/id/me" -> MockResponse().setBody("""{"success":true,"data":{"hex":"00b2","handle":"ada@acme.example",
                        "name":"Ada A.","kind":"member","tenant":{"hex":"00t9","name":"Acme","role":null,"owner":false},"photo":null,
                        "center":null,"unlock":"self","session":"sB","group":"g2"}}""")
                    path == "/id/links" -> MockResponse().setResponseCode(403)
                        .setBody("""{"success":false,"error":{"status":403,"kind":"access.denied","message":"links are off"}}""")
                    path.startsWith("/id/sessions") && request.method == "DELETE" -> {
                        deletes += "$path by $auth"
                        MockResponse().setBody("""{"success":true,"data":{"ended":1}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            fun client(refresh: String, session: String) = Aduki.builder().endpoint(server.url("/v1").toString())
                .identity(server.url("/id").toString()).token("t").build()
                .also { it.session.update(Tokens("", refresh, "", session)) }
            val a = client("rA", "sA")
            val b = client("rB", "sB")
            val clients = mapOf("00a1" to a, "00b2" to b)
            val center = AccountsCenter(context, directory, object : AccountsCenter.Sessions {
                override fun client(hex: String) = clients[hex]
            })
            assertTrue(center.add(null, a, null) is AccountsCenter.AddResult.DeviceOnly)

            val added = center.add(a, b, "adkl_ticket")
            assertEquals(AccountsCenter.AddResult.DeviceOnly(added.account, Refusal.TenantDenies), added)
            assertEquals("Acme", directory.find("00b2")!!.profile.tenant?.name)
            assertEquals(listOf("00a1", "00b2"), center.accounts.value.map { it.hex })
            assertEquals(Refusal.TenantDenies, center.accounts.value[1].refusal)

            val switched = center.switch("00b2")
            assertTrue(switched is AccountsCenter.SwitchResult.Ready)
            assertEquals("00b2", center.active())
            assertEquals(AccountsCenter.SwitchResult.Unknown, center.switch("ffff"))

            // Remove from this device: its own session ends its group, the entry goes for every app.
            assertTrue(center.removeFromDevice("00b2"))
            assertEquals(listOf("/id/sessions?group=g2 by Bearer id-rB"), deletes.toList())
            assertNull(directory.find("00b2"))
            assertNull(center.active())
            assertNotNull(directory.find("00a1"))
        } finally {
            server.shutdown()
        }
    }
}
