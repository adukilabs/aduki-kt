package pro.aduki.core.models

import java.time.Instant

/**
 * A call in Aduki Calls: a direct or group call, a meeting, a room, a Chats
 * thread's call or a call centre's.
 *
 * [call] is its sixteen-hex id (what a ticket and the signalling socket
 * take). A meeting or room made since letter codes has a [code]
 * (`abc-defg-hij`) and a [link] to share; older ones have neither and are
 * joined by [call]. Times are null when the server left them out.
 */
data class Call(
    val call: String,
    /** `direct`, `group`, `meeting`, `room`, `thread` or `center`. */
    val kind: String,
    /** `open`, `live` or `expired`. */
    val state: String,
    val participants: Int = 0,
    /** The account hex of whoever made it. */
    val owner: String = "",
    /** The owner's profile name, else their handle; null when Calls knows neither. */
    val ownerName: String? = null,
    val ownerHandle: String? = null,
    val created: Instant? = null,
    val expires: Instant? = null,
    /** The signalling socket URL (`wss://.../v1/signal`). */
    val signal: String = "",
    /** The room the call client joins on that socket. */
    val room: String = "",
    /** A meeting's or room's letter code, else null. */
    val code: String? = null,
    /** The link to share for a meeting or room with a [code] (`https://calls.aduki.pro/call/abc-defg-hij`). */
    val link: String? = null,
    val title: String? = null,
    /** A meeting's host (account hex), who may end and restart it. */
    val host: String? = null,
    /** When its host ended it for everyone; it can be restarted with `reopen`. */
    val closed: Instant? = null,
    /** When its host locked it (only its parties get in), else null. */
    val locked: Instant? = null,
    /** The waiting room: `off`, `guests` or `everyone`; null for calls that have none. */
    val lobby: String? = null,
    /** `reopen`'s answer: it was closed or expired and is open again. */
    val restarted: Boolean = false,
    /** When people were rung: how many, and how many of their devices. */
    val ringing: Ringing? = null,
    /** The owner's view: everyone rung and their answer. */
    val invites: List<Invite> = emptyList(),
    /** A rung person's view: their own answer. */
    val invite: Invite? = null,
    /** A thread's call: its Chats thread. */
    val thread: String? = null
) {
    /** True for a meeting or a room (they have links and a lobby). */
    val meeting: Boolean get() = kind == "meeting" || kind == "room"

    /** How many people and devices a new call rang. */
    data class Ringing(val people: Int, val devices: Int)

    /**
     * One person rung: their [account] (blank in a rung person's own view),
     * their answer [state] (`ringing`, `answered`, `declined`, `missed`) and,
     * for the owner, their name and handle.
     */
    data class Invite(
        val account: String = "",
        val state: String,
        val since: Instant? = null,
        val name: String? = null,
        val handle: String? = null
    )
}

/**
 * What `GET /v1/meetings/{code}` tells anyone about a meeting code: nothing
 * about who is in it.
 */
data class MeetingStatus(
    /** A meeting or room has this code (a direct, group or thread call says false, as an unknown code does). */
    val exists: Boolean,
    /** Anyone with the code may join now: not ended, not locked, not a booked meeting. */
    val open: Boolean,
    /** Closed, expired, or emptied more than ten minutes ago: only its organiser gets in again. */
    val ended: Boolean,
    /** The signed-in caller made or hosts it and may restart it (`reopen`). */
    val organiser: Boolean = false,
    /** An open meeting's title, when it has one. */
    val title: String? = null,
    /** Its sixteen-hex id, when it exists. */
    val call: String? = null,
    /** Its letter code, when it exists and has one. */
    val code: String? = null
)

/** A single-use ticket into [call], presented once on the signalling socket within [expires] seconds. */
data class CallTicket(val ticket: String, val expires: Long, val call: String)

/**
 * The answer to asking for a way into a call: a [Ticket], or [Waiting] when
 * the call's lobby holds the caller (wait for the socket's `admitted`, then
 * ask again).
 */
sealed interface CallEntry {
    data class Ticket(val ticket: CallTicket) : CallEntry
    data class Waiting(val call: String, val since: Instant?) : CallEntry
}

/** The missed-calls badge, alike on every device: [count] since the person last looked ([seen]). */
data class MissedCalls(val count: Int, val seen: Instant?)

/** One page of a list and the cursor of the next page, null on the last. */
data class CallPage<T>(val items: List<T>, val next: String?)

/**
 * A device that rings for calls: its [id], [platform] (`android`, `ios`,
 * `web`), its push kind (`fcm`, `webpush`, `none`), [label] and [state].
 */
data class CallDevice(
    val id: String,
    val platform: String,
    val push: String,
    val label: String? = null,
    val state: String = ""
)

/**
 * The person's Calls plan (`GET /v1/limits`): the [plan] package, its
 * `audio.*` [limits] (a number, a switch, or null for unlimited), what is
 * [used] of the counted keys this UTC month (from [from] to [to]) and which
 * keys the server [enforced]. A new call past them is `403 plan.limit`.
 */
data class CallLimits(
    val plan: String,
    val limits: Map<String, Any?>,
    val used: Map<String, Long>,
    val enforced: List<String>,
    val from: Instant? = null,
    val to: Instant? = null
) {
    /** The numeric limit [key] (`audio.calls`, `audio.minutes`, ...), null when unlimited or not a number. */
    fun limit(key: String): Long? = (limits[key] as? Number)?.toLong()

    /** Whether the switch [key] (`audio.record`, `audio.pstn`, ...) is on. */
    fun allows(key: String): Boolean = limits[key] == true
}
