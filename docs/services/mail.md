# Mail Service Reference

The `Mail` service (`client.mail`) provides high-level APIs for composing, querying, flagging, and managing email messages and mailbox folders with zero-copy ObjectBox backing.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.hermes.sdk

class Mail internal constructor(...) {
    suspend fun send(
        to: List<String>,
        subject: String,
        body: String,
        mailbox: String = "outbox"
    ): Message

    suspend fun flag(hex: String, flag: Int)

    suspend fun move(hex: String, dest: String)

    suspend fun remove(hex: String)

    fun observe(mailboxHex: String): StateFlow<List<Message>>?

    fun mailboxes(): StateFlow<List<Mailbox>>?

    fun unread(mailboxHex: String): StateFlow<Int>?
}
```

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
| `hex` | `String` | Yes | Unique 64-character message hexadecimal identifier. |
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
- **Zero-Copy Performance**: ObjectBox subscribers use query observers without allocating intermediate cursor cursors or JSON serialization.

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
package pro.aduki.hermes.store.entities

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
package pro.aduki.hermes.store.entities

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

`pro.aduki.hermes.net.http.Mail` is the typed client for the Hermes REST mail endpoints, on the client's authenticated connection. Its calls are blocking, so run them off the main thread. The sync engine and the outbox use it through `HttpMailboxTransport` and `HttpDispatcher` (see [Sync Engine](sync.md) and [Offline Outbox](outbox.md)).

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

Errors are `HermesException.Auth` (401/403), `HermesException.Network` (transport failures, and other HTTP errors with `code`), and `HermesException.Protocol` (an unparseable response). The DTOs (`MessageRow`, `MailChanges`, `FlagUpdate`, `MailboxRow`, `Listing`, `Moved`, `Address`) live in `pro.aduki.hermes.core.models`.

