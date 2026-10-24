# Mail Service Reference

The `Mail` service (`client.mail`) provides high-level APIs for composing, querying, flagging, and managing email messages and mailbox folders backed by the local ObjectBox store.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Mail internal constructor(...) {
    // Compose (#31)
    suspend fun send(to: List<String>, subject: String, body: String, mailbox: String = "outbox"): Message
    suspend fun send(draft: Draft, mailbox: String = "outbox"): Message

    // Flags, moves, deletes (#26)
    suspend fun flag(hex: String, flag: Int)                       // toggles
    suspend fun setFlag(hex: String, flag: Int, on: Boolean)       // idempotent
    suspend fun setFlags(hexes: List<String>, flag: Int, on: Boolean)
    suspend fun markAllRead(mailbox: String)
    suspend fun move(hex: String, dest: String)
    suspend fun move(hexes: List<String>, dest: String)
    suspend fun remove(hex: String)
    suspend fun remove(hexes: List<String>)

    // Mailboxes (#28)
    suspend fun createMailbox(name: String, parent: String? = null): Mailbox
    suspend fun renameMailbox(hex: String, name: String)
    suspend fun deleteMailbox(hex: String)

    // Bodies and attachments (#24)
    suspend fun body(hex: String, refresh: Boolean = false): Body
    suspend fun attachments(hex: String): List<Attachment>
    fun open(attachment: Attachment): InputStream
    suspend fun save(attachment: Attachment, file: File): File

    // Search and conversations (#25, #29)
    fun search(query: String, mailbox: String? = null): StateFlow<List<Message>>?
    suspend fun find(query: String, mailbox: String? = null, page: Int = 1, limit: Int = 50): List<Message>
    fun thread(threadId: String): StateFlow<List<Message>>?
    fun conversations(mailboxHex: String): StateFlow<List<Conversation>>?

    // Snooze (#27)
    suspend fun snooze(hex: String, until: Instant)
    suspend fun unsnooze(hex: String)
    suspend fun wake(now: Instant = Instant.now()): Int

    fun observe(mailboxHex: String): StateFlow<List<Message>>?
    fun mailboxes(): StateFlow<List<Mailbox>>?
    fun unread(mailboxHex: String): StateFlow<Int>?
}
```

Every write is applied to the local store at once and queued in the outbox (see [Outbox](outbox.md)); `client.sync.flush()` sends the queue, and `send` and `createMailbox` flush by themselves. Without a local store (`Aduki.Builder.store`) writes throw `IllegalStateException("no local store")` and reads return `null`.

---

## 2. Detailed Method Specifications

### `send`
Enqueues an outbound email message for optimistic local storage and immediate background network dispatch.

```kotlin
suspend fun send(
    to: List<String>,
    subject: String,
    body: String,
    mailbox: String = "outbox"
): Message
```

#### Parameters

| Parameter | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `to` | `List<String>` | Yes | — | List of recipient email addresses (e.g. `listOf("partner@example.com")`). Joined internally as comma-delimited string for FlatBuffers storage. |
| `subject` | `String` | Yes | — | Email subject line. |
| `body` | `String` | Yes | — | Email body text (UTF-8). |
| `mailbox` | `String` | No | `"outbox"` | Mailbox folder identifier to which the draft/queued message is initially written. |

#### Return Value
- **Type**: `Message`
- **Description**: The persisted `Message` entity containing assigned `hex`, snippet preview (first 120 chars), timestamp, and `dirty = true`.

#### Concurrency & Execution
- **Thread Context**: Executes within caller's coroutine dispatcher (recommended `Dispatchers.IO`).
- **Persistence**: Writes `Message` and journals an `Outbox` mutation (`action = "send"`) in an atomic ObjectBox transaction. Immediately signals `Worker.drain()` to begin network dispatch.

---

### `flag`
Optimistically toggles a flag bitmask on an existing message and queues a flag sync mutation.

```kotlin
suspend fun flag(hex: String, flag: Int)
```

#### Parameters

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `hex` | `String` | Yes | Message hex identifier. |
| `flag` | `Int` | Yes | Bitmask integer to XOR toggle against the message's current `flags`. |

#### Bitmask Constants (`Message.Companion`)

| Constant | Value | Description |
| :--- | :--- | :--- |
| `Message.SEEN` | `1` (`1 << 0`) | Read / Unread status. |
| `Message.ANSWERED` | `2` (`1 << 1`) | Replied to status. |
| `Message.FLAGGED` | `4` (`1 << 2`) | Starred / High priority flag. |
| `Message.DELETED` | `8` (`1 << 3`) | Deleted / Scheduled for purge. |
| `Message.DRAFT` | `16` (`1 << 4`) | Unsent draft message. |

---

### `move`
Relocates a message to a destination mailbox folder.

```kotlin
suspend fun move(hex: String, dest: String)
```

#### Parameters

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `hex` | `String` | Yes | Unique message hexadecimal identifier. |
| `dest` | `String` | Yes | Destination mailbox identifier (e.g. `"archive"`, `"trash"`, `"sent"`). |

---

### `remove`
Deletes a message from local storage and queues a remote purge mutation.

```kotlin
suspend fun remove(hex: String)
```

#### Parameters

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `hex` | `String` | Yes | Unique message hexadecimal identifier. |

---

### `observe`
Returns a hot, reactive `StateFlow` providing real-time emissions whenever messages in a mailbox change.

```kotlin
fun observe(mailboxHex: String): StateFlow<List<Message>>?
```

- **Parameters**: `mailboxHex: String` — Mailbox identifier (e.g., `"inbox"`).
- **Return Type**: `StateFlow<List<Message>>?` — Emits sorted list of `Message` entities. Returns `null` if local store is uninitialized.

---

### `mailboxes`
Returns a hot `StateFlow` observing the full collection of mailboxes and CONDSTORE sequence counters.

```kotlin
fun mailboxes(): StateFlow<List<Mailbox>>?
```

- **Return Type**: `StateFlow<List<Mailbox>>?` — List of `Mailbox` entities including `unseen` and `exists` counts.

---

### `unread`
Returns a hot `StateFlow` observing the count of unread messages (`flags & SEEN == 0`) for a mailbox.

```kotlin
fun unread(mailboxHex: String): StateFlow<Int>?
```

- **Parameters**: `mailboxHex: String` — Mailbox identifier.
- **Return Type**: `StateFlow<Int>?` — Reactive unread badge counter.

---

## 3. Data Models

### `Message` Entity

```kotlin
package pro.aduki.store.entities

@Entity
data class Message(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var mailbox: String = "",
    @Index var uid: Long = 0,
    @Index var threadId: String = "",
    var subject: String = "",
    var fromName: String = "",
    var fromEmail: String = "",
    var to: String = "",            // comma-separated addresses
    var preview: String = "",       // was `snippet` before 0.2.0
    var blob: String = "",
    var size: Long = 0,
    @Index var flags: Int = 0,      // SEEN=1, ANSWERED=2, FLAGGED=4, DELETED=8, DRAFT=16
    var keywords: String = "",      // other server flags, space-separated ("$Junk $Forwarded")
    var hasAttachment: Boolean = false,
    @Index var receivedAt: Long = 0, // was `date` before 0.2.0; epoch millis UTC
    var sentAt: Long = 0,
    var modseq: Long = 0,
    var created: Long = 0,
    var dirty: Boolean = false
) {
    fun seen(): Boolean
    fun flagged(): Boolean
    fun deleted(): Boolean           // `\Deleted`: hidden from lists and unread counts
    fun toggle(flag: Int)
    fun recipients(): List<String>
    fun sender(): String             // fromName, else fromEmail

    companion object {
        fun bits(flags: List<String>): Int        // server flags -> bitmask
        fun keywords(flags: List<String>): String // the non-system flags
        fun flagName(bit: Int): String?           // SEEN -> "\Seen"
    }
}
```

### `Mailbox` Entity

```kotlin
package pro.aduki.store.entities

@Entity
data class Mailbox(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    var name: String = "",
    var role: String = "", // inbox, sent, trash, archive, drafts
    var uidnext: Long = 0,
    var uidvalidity: Long = 0,
    var modseq: Long = 0,
    var exists: Int = 0,
    var unseen: Int = 0
)
```

---

## 4. REST Client: `client.mailApi`

`pro.aduki.net.http.Mail` is the typed client for the Aduki REST mail endpoints, on the client's authenticated connection. Its calls are blocking, so run them off the main thread. The sync engine and the outbox use it through `HttpMailboxTransport` and `HttpDispatcher` (see [Sync Engine](sync.md) and [Offline Outbox](outbox.md)).

| Method | Endpoint | Returns |
| :--- | :--- | :--- |
| `inbox(after, limit)` | `GET /v1/user/mail/inbox` | `Listing<MessageRow>` (cursor in `next`) |
| `folder(mailbox, page, limit)` | `GET /v1/user/mail/folder/{hex}` | `Listing<MessageRow>` |
| `thread(thread, page, limit)` | `GET /v1/user/mail/thread/{thread}` | `Listing<MessageRow>` |
| `changes(mailbox, since, uidvalidity, limit)` | `GET /v1/user/mail/changes` | `MailChanges` |
| `flags(hex, add, remove)` | `PATCH /v1/user/mail/{hex}/flags` | — |
| `move(hex, mailbox)` | `PATCH /v1/user/mail/{hex}/mailbox` | `Moved` (same id, new UID) |
| `delete(hex)` | `DELETE /v1/user/mail/{hex}` | — |
| `send(to, subject, text, cc, from, idempotencyKey)` | `POST /v1/user/mail/send` | server id of the sent copy |
| `mailboxes(page, limit)` | `GET /v1/user/mailbox` | `Listing<MailboxRow>` |

Errors are `AdukiException.Auth` (401/403), `AdukiException.Network` (transport failures, and other HTTP errors with `code`), and `AdukiException.Protocol` (an unparseable response). The DTOs (`MessageRow`, `MailChanges`, `FlagUpdate`, `MailboxRow`, `Listing`, `Moved`, `Address`) live in `pro.aduki.core.models`.

## 5. Composing: `Draft`

```kotlin
val draft = Draft(
    to = listOf("ada@example.com"),
    cc = listOf("bob@example.com"),
    bcc = listOf("audit@example.com"),
    subject = "Re: Lunch",
    text = "Noon works.",
    html = "<p>Noon works.</p>",
    attachments = listOf(Upload("menu.pdf", "application/pdf", path = file.path)),
    reply = original.hex            // or forward = original.hex
)
client.mail.send(draft)
```

- A plain draft (text only, no bcc, attachments, HTML, display name, reply or forward) is sent with `POST /v1/user/mail/send` and an `Idempotency-Key`.
- Any other draft is sent with JMAP. The attachments are uploaded, the message is created in Drafts (`Email/set`) and submitted (`EmailSubmission/set`). On success it is filed in Sent and `$draft` is cleared.
- A reply carries `In-Reply-To` and `References` built from the original's Message-ID, so it threads on the server. The original gets `$answered` (a forward gets `$forwarded`).
- `from` must be one of the account's sending identities; the server refuses any other address. Without `from`, the default sender (`Sender.default`, JMAP `isDefault`) is used, over REST and JMAP alike.
- Retries are safe. The Message-ID is derived from the outbox entry, so a retry whose first attempt got through finds that message and does not send again.
- An `Upload` holds `bytes` or a `path`. Bytes are kept in the (sealed) outbox until sent. A path must stay readable until then: use one for large files.

## 6. Bodies and attachments

`body(hex)` reads the text and HTML parts and the attachment list with JMAP `Email/get`. With a local store, the result is cached in the sealed `Content` entity and read offline afterwards; `refresh = true` fetches it again. Lists never load bodies. `open(attachment)` streams the blob (`/jmap/download`), and `save(attachment, file)` writes it to a file.

## 7. Search and conversations

- `search(query, mailbox?)` matches synced messages offline. Every word must appear in the subject, sender, recipients or preview. The columns are sealed, so matching runs in memory.
- `find(query, mailbox?)` asks the server (`GET /v1/user/mail/search/{q}`), which also covers messages not synced. Its results are not stored.
- `thread(threadId)` gives one conversation across mailboxes, oldest first.
- `conversations(mailbox)` gives one `Conversation(latest, count, unread)` per thread, newest first.

## 8. Mailboxes

`createMailbox` shows the mailbox at once under a placeholder hex (`Mailbox.pending()`, prefix `local-`). When the queued create is sent, the row, its messages and every queued action that names it switch to the server's hex. `renameMailbox` and `deleteMailbox` are queued like moves. Deleting a mailbox also drops its messages.

## 9. Snooze

The server has no snooze, so the SDK keeps it in data that syncs:
- Snoozing moves the message to a mailbox named "Snoozed" (created when missing) and adds the keyword `$snoozed-<epoch seconds>`.
- `Message.snoozed()` reads the time back.
- After each sync, `client.sync.all()` calls `wake()`, which brings due messages back to the inbox, unread. Any device that syncs does it.
- A message comes back on the first sync after its time, so schedule periodic syncs (e.g. WorkManager) for it to reappear on time.
