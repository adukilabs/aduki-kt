package pro.aduki.sdk

import okhttp3.OkHttpClient
import org.json.JSONObject
import pro.aduki.core.calls.Meetings
import pro.aduki.core.config.Endpoints
import pro.aduki.core.models.Call
import pro.aduki.core.models.CallDevice
import pro.aduki.core.models.CallEntry
import pro.aduki.core.models.CallLimits
import pro.aduki.core.models.CallPage
import pro.aduki.core.models.CallTicket
import pro.aduki.core.models.MeetingStatus
import pro.aduki.core.models.MissedCalls
import pro.aduki.net.http.Calls as NetCalls

/**
 * Calls and meetings in Aduki Calls for the signed-in account
 * (`Aduki.calls`): start a call, make a meeting and share its link or code,
 * list and join meetings, restart one that ended, the call log and the
 * devices that ring.
 *
 * Aduki Calls takes only DPoP-bound tokens of audience `audio`: build the
 * client with `Aduki.Builder.dpop(key)` and an Aduki ID session. The `audio`
 * token is renewed with the client's own refresh token, under the same lock
 * as the mail token, and the rotated refresh token is saved.
 *
 * Every call runs on `Dispatchers.IO` and fails with `AdukiException` (or
 * `IllegalArgumentException` for a wrong argument, such as an id that is not
 * sixteen hex digits). The media of a call is Aduki Calls' own SDK: sign it
 * in with [token] and [proof].
 */
class Calls internal constructor(private val net: () -> NetCalls) {

    /** The HTTP client of this API, for calls this facade does not cover. */
    val api: NetCalls get() = net()

    // ---- calls and meetings -----------------------------------------------------------------------------------------

    /** Starts a call and rings [to] (account hexes, at most 20). */
    suspend fun start(to: List<String> = emptyList()): Call = guard { api.start(to) }

    /**
     * Makes a meeting, open by its link until its host locks it. [title] is
     * at most 120 characters, [lobby] its waiting room (`off`, `guests` or
     * `everyone`). Share [Call.link] or [Call.code]; join it with [join].
     */
    suspend fun meeting(title: String? = null, lobby: String? = null): Call = guard { api.meeting(title, lobby) }

    /** One call or meeting; [call] is its hex, a meeting code or a meeting link. */
    suspend fun get(call: String): Call = guard { api.fetch(Meetings.require(call, "call")) }

    /**
     * The account's calls and meetings, newest first ([limit] 1 to 100; pass
     * the page's `next` as [after]). [missed] keeps the missed ones, [kind]
     * one kind (`meeting` for the meetings), [with] those an account was in.
     */
    suspend fun list(
        limit: Int? = null,
        after: String? = null,
        missed: Boolean = false,
        kind: String? = null,
        with: String? = null
    ): CallPage<Call> = guard { api.list(limit, after, missed, kind, with) }

    /** The meetings the account made or was in, newest first: [list] with kind `meeting`. */
    suspend fun meetings(limit: Int? = null, after: String? = null): CallPage<Call> = list(limit, after, kind = "meeting")

    /** The calls ringing for the account now (after a ring's push woke the app). */
    suspend fun ringing(): List<Call> = guard { api.ringing() }

    /**
     * A way into a call or meeting ([call]: its hex, a meeting code or a
     * link): a ticket for the call client's socket, or [CallEntry.Waiting]
     * while the meeting's lobby holds the account.
     */
    suspend fun join(call: String): CallEntry = guard { api.enter(call) }

    /** Whether a meeting code (or link) names a meeting, whether it is open now and whether it ended. */
    suspend fun status(code: String): MeetingStatus = guard { api.meetingStatus(code) }

    /**
     * Restarts a meeting the account made or hosts after it ended (closed
     * or expired): it is open again for a day with the same code and link.
     * [call] is its hex or meeting code.
     */
    suspend fun restart(call: String): Call = guard {
        val code = Meetings.require(call, "call")
        api.reopen(if (code.length == 16) code else api.fetch(code).call)
    }

    /** Ends a meeting for everyone (its host). */
    suspend fun end(call: String): Call = guard { api.end(call) }

    /** Changes a meeting the account hosts: lock it, rename it, its lobby, or who may unmute. */
    suspend fun change(
        call: String,
        locked: Boolean? = null,
        title: String? = null,
        lobby: String? = null,
        unmute: String? = null
    ): Call = guard { api.change(call, locked, title, lobby, unmute) }

    /** Rings more people (account hexes, 1 to 20) into a call. */
    suspend fun invite(call: String, to: List<String>): Call = guard { api.invite(call, to) }

    /** Answers a ring: accepted, a ticket into the call; declined, null. */
    suspend fun answer(call: String, accept: Boolean): CallTicket? = guard { api.answer(call, accept) }

    /** Leaves [call] on every device of the account. */
    suspend fun leave(call: String): Unit = guard { api.leave(call) }

    // ---- the call log -----------------------------------------------------------------------------------------------

    /** How many calls were missed since the account last looked. */
    suspend fun missed(): MissedCalls = guard { api.missed() }

    /** The account looked at its missed calls: the count is zero again on every device. */
    suspend fun seenMissed(): MissedCalls = guard { api.seenMissed() }

    /** Hides one call from the account's log. */
    suspend fun hide(call: String): Unit = guard { api.hide(call) }

    /** Clears the account's whole call log. */
    suspend fun clearLog(): Unit = guard { api.clearLog() }

    // ---- the plan, devices and the media SDK ------------------------------------------------------------------------

    /** The account's Calls plan: limits and what is used this month. */
    suspend fun limits(): CallLimits = guard { api.limits() }

    /** TURN servers for the account (`servers`, `ttl`, `expires`, `policy`). */
    suspend fun relays(): JSONObject = guard { api.relays() }

    /** Registers this device to ring, with its FCM [token] (null: it rings nowhere). */
    suspend fun register(token: String?, label: String? = null): CallDevice = guard { api.register("android", token, label) }

    /** The account's devices that ring. */
    suspend fun devices(): List<CallDevice> = guard { api.devices() }

    /** A new FCM [token] or [label] for [device]. */
    suspend fun edit(device: String, token: String? = null, label: String? = null): CallDevice =
        guard { api.edit(device, token, label) }

    /** Stops [device] ringing (sign-out). */
    suspend fun remove(device: String): Unit = guard { api.remove(device) }

    /** A current `audio` access token for Aduki Calls' media SDK. Blocking: call it off the main thread. */
    fun token(): String = api.token()

    /** A DPoP proof for the media SDK's request [method] on [url] bound to [token]. */
    fun proof(method: String, url: String, token: String): String = api.proof(method, url, token)

    companion object {
        /** The link to share for meeting [code] (`https://calls.aduki.pro/call/abc-defg-hij`). */
        fun link(code: String): String = Meetings.link(code)

        /** The meeting code in what was typed or pasted (a code in any form, or a link), or null. */
        fun code(text: String?): String? = Meetings.code(text)

        /**
         * [status] without an account, for a join screen before sign-in. On
         * `Dispatchers.IO`.
         */
        suspend fun status(code: String, base: String = Endpoints.CALLS, http: OkHttpClient? = null): MeetingStatus =
            guard { NetCalls.status(http ?: plain, code, base) }

        // One client without credentials for the signed-out reads, built on first use.
        private val plain: OkHttpClient by lazy {
            OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        }
    }
}
