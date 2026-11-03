package pro.aduki.android.account

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Key
import pro.aduki.net.http.Handoff
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Callers decides who may use a [SuiteBrokerService]: the calling uid must
 * belong to a package in [packages] that is signed with the **same
 * certificate as this app** (`PackageManager.checkSignatures`, which must
 * say `SIGNATURE_MATCH`). There is no debug or test bypass: an APK signed with
 * another key (a debug build next to a Play build) is refused, as is an
 * unknown package that declared the permission itself.
 */
class Callers(context: Context, packages: Collection<String>) {
    private val pm: PackageManager = context.packageManager
    private val own: String = context.packageName
    private val allowed: Set<String> = packages.toSet()

    /** The package calling as [uid] if it is admitted, else null. */
    fun admit(uid: Int): String? {
        val names = runCatching { pm.getPackagesForUid(uid) }.getOrNull() ?: return null
        return names.firstOrNull { it in allowed && same(it) }
    }

    /** True when [pkg] is installed and signed like this app. */
    fun same(pkg: String): Boolean =
        runCatching { pm.checkSignatures(pkg, own) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)
}

/**
 * SuiteBrokerService is the provider side of the on-demand handoff broker
 * (plan section 3): a sibling app that holds no session of an account binds
 * to it, and this app, signed in to that account, asks Aduki ID for a
 * single-use code bound to the **caller's** DPoP key (`POST /v1/handoffs
 * {target, jkt, ttl: 120}`). No token crosses apps; the code is useless
 * without the caller's key.
 *
 * Subclass it once in the app and declare it exported, protected by the
 * publisher's signature permission:
 * ```xml
 * <permission android:name="pro.aduki.permission.ACCOUNT_ACCESS" android:protectionLevel="signature" />
 * <service android:name=".Broker" android:exported="true"
 *     android:permission="pro.aduki.permission.ACCOUNT_ACCESS">
 *     <intent-filter><action android:name="pro.aduki.action.ACCOUNT_BROKER" /></intent-filter>
 * </service>
 * ```
 * Every call is also checked with [Callers]: the caller's package must be in
 * [packages] and signed with this app's certificate.
 */
abstract class SuiteBrokerService : Service() {

    /** The sibling packages that may call (the suite's apps). */
    protected abstract val packages: List<String>

    /** True when this app holds a live session of the account [hex]. */
    protected abstract suspend fun hasSession(hex: String): Boolean

    /**
     * A code for the app [target] to open its own session of [hex], bound to
     * its key [jkt]: `aduki.handoff(target, Handoff.BROKER_TTL, jkt)` with
     * this app's client of [hex]. Null when this app holds no session of it.
     * A failure may throw: the caller is told and tries another provider.
     */
    protected abstract suspend fun handoff(hex: String, target: String, jkt: String): Handoff?

    /** A sibling signed out of every app of the device group [group]: end this app's session of it. */
    protected open suspend fun signedOut(group: String) {}

    /** A sibling asked every app to switch to [hex] ("Use in all Aduki apps"). */
    protected open suspend fun activate(hex: String) {}

    /** How long one call may take, ms. */
    protected open val timeout: Long = 15_000

    private val guard by lazy { Callers(this, packages) }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code !in PING..ACTIVATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val caller = guard.admit(Binder.getCallingUid())
            if (caller == null) {
                reply?.writeInt(REFUSED)
                return true
            }
            serve(code, caller, data, reply)
            return true
        }
    }

    // One call, on a binder thread (blocking is expected there).
    private fun serve(code: Int, caller: String, data: Parcel, reply: Parcel?) {
        val first = data.readString().orEmpty()
        val second = data.readString().orEmpty()
        runBlocking {
            val done = withTimeoutOrNull(timeout) {
                try {
                    when (code) {
                        PING -> reply?.apply { writeInt(OK); writeInt(if (hasSession(first)) 1 else 0) }
                        HANDOFF -> {
                            if (second.isBlank()) {
                                reply?.writeInt(FAILED)
                            } else {
                                val handoff = handoff(first, caller, second)
                                if (handoff == null) {
                                    reply?.writeInt(NO_SESSION)
                                } else reply?.apply {
                                    writeInt(OK)
                                    writeString(handoff.code)
                                    // Seconds left, never an absolute time: the clocks may differ.
                                    writeLong((handoff.expires - System.currentTimeMillis() / 1000).coerceAtLeast(0))
                                }
                            }
                        }
                        SIGNED_OUT -> { signedOut(first); reply?.writeInt(OK) }
                        ACTIVATE -> { activate(first); reply?.writeInt(OK) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AdukiException.Network) {
                    reply?.writeInt(OFFLINE)
                } catch (e: IOException) {
                    reply?.writeInt(OFFLINE)
                } catch (e: Exception) {
                    reply?.writeInt(FAILED)
                }
                true
            }
            if (done == null) reply?.writeInt(OFFLINE)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        /** The intent action of the broker service. */
        const val ACTION = "pro.aduki.action.ACCOUNT_BROKER"

        /** The signature permission that protects it (declared by the app). */
        const val PERMISSION = "pro.aduki.permission.ACCOUNT_ACCESS"

        internal const val DESCRIPTOR = "pro.aduki.android.account.SuiteBroker"
        internal const val PING = IBinder.FIRST_CALL_TRANSACTION
        internal const val HANDOFF = PING + 1
        internal const val SIGNED_OUT = PING + 2
        internal const val ACTIVATE = PING + 3

        internal const val OK = 0
        internal const val NO_SESSION = 1
        internal const val REFUSED = 2
        internal const val OFFLINE = 3
        internal const val FAILED = 4
    }
}

/**
 * SuiteBroker is the caller side of the broker: it asks signed-in sibling
 * apps, one after another, for a code bound to this app's DPoP key and
 * redeems it with that key. Siblings are bound only if installed and signed
 * with this app's certificate ([Callers.same]).
 *
 * [packages] are the suite's apps (this app is skipped).
 */
class SuiteBroker(
    context: Context,
    packages: List<String>,
    /** How long binding to and calling one sibling may take, ms. */
    private val timeout: Long = 8_000,
    /** The pause before the one retry after a network failure, ms. */
    private val backoff: Long = 1_500,
    private val link: BrokerLink = BrokerLink.system(context)
) {
    private val app = context.applicationContext
    private val own = app.packageName
    private val siblings = packages.filter { it != own }
    private val callers = Callers(app, packages)

    /** Why no sibling could open a session: what the password screen says. */
    enum class Reason {
        /** No other suite app is installed (or none signed like this one). */
        NoSibling,

        /** The siblings are installed but none is signed in to this account ("Mail is signed out too"). */
        SignedOut,

        /** A sibling or Aduki ID could not be reached ("No network"). */
        Offline,

        /** The siblings refused the call or the code (a signing mismatch, a key mismatch). */
        Refused
    }

    /** The outcome of [open]. */
    sealed interface Pull<out T> {
        /** A session was opened from [provider]'s code. */
        data class Opened<T>(val value: T, val provider: String) : Pull<T>

        /** No sibling could help: ask for the password, saying [reason]. */
        data class NeedsPassword(val reason: Reason, val tried: List<String>) : Pull<Nothing>
    }

    /** The installed siblings signed like this app that hold a session of [hex], in [packages] order. */
    suspend fun providers(hex: String): List<String> =
        installed().filter { pkg -> call(pkg, SuiteBrokerService.PING, hex, "")?.let { it.status == SuiteBrokerService.OK && it.flag } == true }

    /** The installed siblings signed like this app (bound or not). */
    fun installed(): List<String> = siblings.filter { link.has(it) && callers.same(it) }

    /**
     * Opens this app's session of [hex] with a code from a sibling: each
     * signed-in sibling in turn mints one bound to [key] (`jkt`), then
     * [redeem] redeems it (e.g. `{ Aduki.exchange(it, dpop = key, tokenStore = ...) }`).
     * A refused or spent code tries the next sibling; after a network failure
     * the round is tried once more after a short pause. Never throws but for
     * cancellation.
     */
    suspend fun <T> open(hex: String, key: Key, redeem: suspend (code: String) -> T): Pull<T> {
        val jkt = key.thumbprint()
        val candidates = installed()
        if (candidates.isEmpty()) return Pull.NeedsPassword(Reason.NoSibling, emptyList())
        var reason = Reason.SignedOut
        repeat(2) { round ->
            var offline = false
            for (pkg in candidates) {
                val answer = call(pkg, SuiteBrokerService.HANDOFF, hex, jkt)
                when (answer?.status) {
                    SuiteBrokerService.OK -> {
                        val code = answer.code ?: continue
                        try {
                            return Pull.Opened(redeem(code), pkg)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: AdukiException.Proof) {
                            reason = Reason.Refused
                        } catch (e: AdukiException.Unauthorized) {
                            // Spent, expired, or the sibling's session ended meanwhile.
                        } catch (e: AdukiException.Network) {
                            offline = true
                        } catch (e: IOException) {
                            offline = true
                        } catch (e: Exception) {
                            reason = Reason.Refused
                        }
                    }
                    SuiteBrokerService.NO_SESSION -> Unit
                    SuiteBrokerService.REFUSED -> reason = Reason.Refused
                    SuiteBrokerService.OFFLINE, null -> offline = true
                    else -> Unit
                }
            }
            if (!offline) return Pull.NeedsPassword(reason, candidates)
            if (round == 0) delay(backoff)
        }
        return Pull.NeedsPassword(Reason.Offline, candidates)
    }

    /** Tells the running siblings the device group [group] signed out. */
    suspend fun signedOut(group: String) = installed().forEach { call(it, SuiteBrokerService.SIGNED_OUT, group, "") }

    /** Asks every sibling to switch to [hex] ("Use in all Aduki apps"). */
    suspend fun activate(hex: String) = installed().forEach { call(it, SuiteBrokerService.ACTIVATE, hex, "") }

    internal class Answer(val status: Int, val flag: Boolean = false, val code: String? = null, val expiresIn: Long = 0)

    // One transaction with [pkg]'s broker; null when it could not be bound or answered.
    private suspend fun call(pkg: String, code: Int, first: String, second: String): Answer? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeout) {
            link.bind(pkg) { remote ->
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(SuiteBrokerService.DESCRIPTOR)
                    data.writeString(first)
                    data.writeString(second)
                    if (!remote.transact(code, data, reply, 0)) return@bind null
                    reply.setDataPosition(0)
                    val status = reply.readInt()
                    when {
                        status != SuiteBrokerService.OK -> Answer(status)
                        code == SuiteBrokerService.PING -> Answer(status, flag = reply.readInt() == 1)
                        code == SuiteBrokerService.HANDOFF -> Answer(status, code = reply.readString(), expiresIn = reply.readLong())
                        else -> Answer(status)
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }
}

/**
 * How [SuiteBroker] reaches a sibling's broker service: [has] tells whether
 * the package has one, [bind] binds, runs the block on the remote binder and
 * unbinds. The system implementation binds with `Context.bindService`; tests
 * replace it.
 */
interface BrokerLink {
    fun has(pkg: String): Boolean
    suspend fun <T> bind(pkg: String, block: (IBinder) -> T?): T?

    companion object {
        fun system(context: Context): BrokerLink = object : BrokerLink {
            private val app = context.applicationContext

            private fun intent(pkg: String) = Intent(SuiteBrokerService.ACTION).setPackage(pkg)

            override fun has(pkg: String): Boolean =
                runCatching { app.packageManager.queryIntentServices(intent(pkg), 0).isNotEmpty() }.getOrDefault(false)

            override suspend fun <T> bind(pkg: String, block: (IBinder) -> T?): T? {
                var connection: ServiceConnection? = null
                val remote = suspendCancellableCoroutine<IBinder?> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (cont.isActive) cont.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {}

                        override fun onBindingDied(name: ComponentName?) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    connection = conn
                    val bound = try {
                        app.bindService(intent(pkg), conn, Context.BIND_AUTO_CREATE)
                    } catch (_: SecurityException) {
                        false // the permission is not granted: another signer
                    }
                    if (!bound) {
                        connection = null
                        runCatching { app.unbindService(conn) }
                        if (cont.isActive) cont.resume(null)
                    }
                    cont.invokeOnCancellation { runCatching { app.unbindService(conn) } }
                }
                return try {
                    remote?.let(block)
                } finally {
                    connection?.let { runCatching { app.unbindService(it) } }
                }
            }
        }
    }
}
