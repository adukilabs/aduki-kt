package pro.aduki.android.account

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Software
import pro.aduki.net.http.Handoff

/**
 * The broker's caller check has no bypass (plan section 10.2): a sibling
 * package signed with another certificate, or an unknown package, is refused
 * before the app is asked for anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrokerTest {
    private lateinit var context: Context
    private val release = Signature(ByteArray(64) { 7 })
    private val debug = Signature(ByteArray(64) { 9 })

    /** The provider under test: answers for one account, records what it was asked. */
    class Provider : SuiteBrokerService() {
        override val packages = listOf(MAIL, CALENDAR, CONTACTS)
        val asked = mutableListOf<Triple<String, String, String>>()
        override suspend fun hasSession(hex: String) = hex == "00a1"
        override suspend fun handoff(hex: String, target: String, jkt: String): Handoff? {
            asked += Triple(hex, target, jkt)
            return if (hex == "00a1") Handoff("adkh_$target", System.currentTimeMillis() / 1000 + 120) else null
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sign(context.packageName, release)
        install(CALENDAR, 20_001, release)
        install(MAIL, 20_002, debug)
        install("com.evil.app", 20_003, release)
    }

    private fun install(pkg: String, uid: Int, signature: Signature) {
        val info = PackageInfo().apply {
            packageName = pkg
            signatures = arrayOf(signature)
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; this.uid = uid }
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(uid, pkg)
    }

    private fun sign(pkg: String, signature: Signature) {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        info.signatures = arrayOf(signature)
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun handoff(binder: IBinder, hex: String, jkt: String): Pair<Int, String?> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        data.writeInterfaceToken(SuiteBrokerService.DESCRIPTOR)
        data.writeString(hex)
        data.writeString(jkt)
        binder.transact(SuiteBrokerService.HANDOFF, data, reply, 0)
        reply.setDataPosition(0)
        val status = reply.readInt()
        val code = if (status == SuiteBrokerService.OK) reply.readString() else null
        data.recycle()
        reply.recycle()
        return status to code
    }

    @Test
    fun theCallerCheckAdmitsOnlyListedPackagesWithTheSameCertificate() {
        val callers = Callers(context, listOf(MAIL, CALENDAR, CONTACTS))
        assertEquals(CALENDAR, callers.admit(20_001))
        assertNull("a sibling signed with another key (debug next to Play) is refused", callers.admit(20_002))
        assertNull("a package outside the suite is refused, however it is signed", callers.admit(20_003))
        assertNull("an unknown uid is refused", callers.admit(29_999))
    }

    @Test
    fun aSignatureMismatchIsRefusedBeforeTheAppIsAsked() {
        val controller = Robolectric.buildService(Provider::class.java).create()
        val service = controller.get()
        val binder = service.onBind(null)

        ShadowBinder.setCallingUid(20_002)
        assertEquals(SuiteBrokerService.REFUSED to null, handoff(binder, "00a1", "thumb"))
        ShadowBinder.setCallingUid(20_003)
        assertEquals(SuiteBrokerService.REFUSED, handoff(binder, "00a1", "thumb").first)
        assertTrue("no code was minted for a refused caller", service.asked.isEmpty())

        ShadowBinder.setCallingUid(20_001)
        assertEquals(SuiteBrokerService.OK to "adkh_$CALENDAR", handoff(binder, "00a1", "thumb"))
        assertEquals(Triple("00a1", CALENDAR, "thumb"), service.asked.single())
        assertEquals("no session of that account", SuiteBrokerService.NO_SESSION, handoff(binder, "00b2", "thumb").first)
        ShadowBinder.reset()
    }

    /** A fake transport: each package answers with a fixed status and code. */
    private class Fake(val answers: Map<String, Pair<Int, String?>>) : BrokerLink {
        val bound = mutableListOf<String>()
        override fun has(pkg: String) = pkg in answers
        override suspend fun <T> bind(pkg: String, block: (IBinder) -> T?): T? {
            bound += pkg
            val (status, code) = answers.getValue(pkg)
            val binder = object : android.os.Binder() {
                override fun onTransact(c: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    reply!!.writeInt(status)
                    if (status == SuiteBrokerService.OK) {
                        if (c == SuiteBrokerService.PING) reply.writeInt(1) else { reply.writeString(code); reply.writeLong(120) }
                    }
                    return true
                }
            }
            return block(binder)
        }
    }

    @Test
    fun openTriesSiblingsInTurnAndRedeemsWithItsOwnKey() = runBlocking {
        sign(MAIL, release)
        install(CONTACTS, 20_004, release)
        val fake = Fake(mapOf(MAIL to (SuiteBrokerService.NO_SESSION to null), CALENDAR to (SuiteBrokerService.OK to "c1"), CONTACTS to (SuiteBrokerService.OK to "c2")))
        val broker = SuiteBroker(context, listOf(context.packageName, MAIL, CALENDAR, CONTACTS), backoff = 0, link = fake)
        val key = Software.p256()
        val redeemed = mutableListOf<String>()
        val pull = broker.open("00a1", key) { code ->
            redeemed += code
            if (code == "c1") throw AdukiException.Unauthorized("spent", null, "auth.invalid")
            "session-from-$code"
        }
        assertEquals(SuiteBroker.Pull.Opened("session-from-c2", CONTACTS), pull)
        assertEquals("a spent code tries the next sibling", listOf("c1", "c2"), redeemed)
        assertTrue("this app is never asked", context.packageName !in fake.bound)
    }

    @Test
    fun openSaysWhyAPasswordIsNeeded() = runBlocking {
        sign(MAIL, release)
        val key = Software.p256()
        val none = SuiteBroker(context, listOf(context.packageName), backoff = 0, link = Fake(emptyMap()))
        assertEquals(SuiteBroker.Reason.NoSibling, (none.open("00a1", key) { it } as SuiteBroker.Pull.NeedsPassword).reason)

        val out = SuiteBroker(context, listOf(MAIL, CALENDAR), backoff = 0,
            link = Fake(mapOf(MAIL to (SuiteBrokerService.NO_SESSION to null), CALENDAR to (SuiteBrokerService.NO_SESSION to null))))
        assertEquals(SuiteBroker.Reason.SignedOut, (out.open("00a1", key) { it } as SuiteBroker.Pull.NeedsPassword).reason)

        val offline = SuiteBroker(context, listOf(CALENDAR), backoff = 0, link = Fake(mapOf(CALENDAR to (SuiteBrokerService.OFFLINE to null))))
        assertEquals(SuiteBroker.Reason.Offline, (offline.open("00a1", key) { it } as SuiteBroker.Pull.NeedsPassword).reason)

        // A sibling signed with another key is not even bound.
        sign(MAIL, debug)
        val fake = Fake(mapOf(MAIL to (SuiteBrokerService.OK to "c1")))
        val mismatch = SuiteBroker(context, listOf(MAIL), backoff = 0, link = fake)
        assertEquals(SuiteBroker.Reason.NoSibling, (mismatch.open("00a1", key) { it } as SuiteBroker.Pull.NeedsPassword).reason)
        assertTrue(fake.bound.isEmpty())
    }

    companion object {
        const val MAIL = "pro.aduki.mail"
        const val CALENDAR = "pro.aduki.calendar"
        const val CONTACTS = "pro.aduki.contacts"
    }
}
