package pro.aduki.core.models

import java.time.Instant

/** A deleted contact in the trash, kept 30 days (`GET /v1/user/contacts/trash`). */
data class TrashedContact(
    /** The trash entry: `restore` and `purge` take it. */
    val id: String,
    /** The contact's hex it gets back. */
    val card: String,
    val name: String?,
    val emails: List<String>,
    val phones: List<String>,
    val groups: List<String>,
    val photo: Boolean,
    /** The address book it was in when that book was deleted, else null. */
    val origin: String?,
    /** The contact it was merged into, else null. */
    val merged: String?,
    val deleted: Instant?,
    /** When it goes for good. */
    val purge: Instant?
)

/** An Aduki account holding one of a contact's addresses (`GET /v1/user/contacts/{hex}/accounts`). */
data class LinkedAccount(
    val address: String,
    val account: String,
    val handle: String,
    /** Only for a colleague of one's workspace. */
    val name: String?,
    val colleague: Boolean,
    /** What it is on: `mail`, `chats`, `calls`. */
    val apps: List<String>
)

/** One recent dealing with a contact (`GET /v1/user/contacts/{hex}/recent`). */
data class Dealing(
    /** `mail` or `event`. */
    val kind: String,
    /** The message's or event's hex. */
    val id: String,
    val title: String?,
    val `when`: Instant?,
    /** Mail: `sent` or `received`; event: `organiser` or `guest`. */
    val way: String
)

/**
 * The Aduki Calls meeting of a calendar event (`/v1/user/events/{hex}/meeting`).
 * [state] is `wanted` (not booked yet), `booked`, `cancelling`, `cancelled`
 * or `failed`; [link] is the members' join link, [guest] the guests' link.
 */
data class EventMeeting(
    val hex: String,
    val title: String,
    val starts: Instant?,
    val ends: Instant?,
    val state: String,
    /** Calls' meeting id. */
    val meeting: String?,
    val link: String?,
    val guest: String?,
    /** Why Calls refused it. */
    val error: String?,
    val updated: Instant?
)
