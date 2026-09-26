# ObjectBox FlatBuffers Entity Schemas Reference

All data models in the Hermes Android SDK are compiled as **ObjectBox FlatBuffers tables**, guaranteeing zero-copy memory-mapped reads and sub-millisecond query latencies.

---

## 1. `Message` Entity

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

### Field Definitions

| Field | Meaning |
| :--- | :--- |
| `hex` | Server id; a `msg_<millis>` placeholder until an outbox send completes. |
| `mailbox`, `uid` | Mailbox hex and the message's UID in it (changes on a move). |
| `threadId` | Conversation id (the message's own id when it has no thread). |
| `fromName`, `fromEmail`, `to` | Parsed sender and recipients. |
| `preview` | Start of the body text, from the server. |
| `flags` | Bitmask of `\\Seen`, `\\Answered`, `\\Flagged`, `\\Deleted`, `\\Draft`. |
| `keywords` | Every other server flag or keyword, space-separated. |
| `hasAttachment` | The message has attachments. |
| `receivedAt`, `sentAt` | Arrival time and `Date:` header, epoch millis (UTC). |
| `modseq` | The server MODSEQ of the last change applied. |
| `dirty` | A local change is waiting in the outbox; sync keeps local flags and mailbox. |

## 2. `Mailbox` Entity

Tracks mailbox folder metadata and RFC 7162 CONDSTORE sequence state.

```kotlin
package pro.aduki.hermes.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

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

### Field Definitions

| Field | Type | Annotation | Description |
| :--- | :--- | :--- | :--- |
| `id` | `Long` | `@Id` | Local ObjectBox primary key. |
| `hex` | `String` | `@Index` | Unique mailbox folder identifier hex. |
| `name` | `String` | — | User-facing folder display title (e.g. `"Inbox"`, `"Work"`). |
| `role` | `String` | — | Special-use role attribute: `"inbox"`, `"sent"`, `"trash"`, `"archive"`, `"drafts"`. |
| `uidnext` | `Long` | — | Predicted next IMAP UID assigned to incoming mail. |
| `uidvalidity` | `Long` | — | Server mailbox generation token. Mismatches trigger full local re-seed. |
| `modseq` | `Long` | — | Highest 64-bit CONDSTORE sequence counter committed locally. |
| `exists` | `Int` | — | Total message count in the folder. |
| `unseen` | `Int` | — | Unread message count badge. |

---

## 3. `Contact` Entity

Stores address book contacts with memory-mapped search indexes.

```kotlin
package pro.aduki.hermes.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Contact(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var name: String = "",
    @Index var email: String = "",
    var phone: String = "",
    var company: String = "",
    var vcard: String = "",
    var ctag: String = "",
    var updated: Long = 0
)
```

---

## 4. `Outbox` Entity

Persists pending offline write-ahead actions for reliable background transmission.

```kotlin
package pro.aduki.hermes.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Outbox(
    @Id var id: Long = 0,
    @Index var action: String = "", // send, flag, move, remove
    var payload: ByteArray = byteArrayOf(),
    @Index var created: Long = System.currentTimeMillis(),
    var attempts: Int = 0,
    var nextRetry: Long = 0
)
```

---

## 5. `Sync` Entity

Persists synchronization cursor tokens and generation timestamps.

```kotlin
package pro.aduki.hermes.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Sync(
    @Id var id: Long = 0,
    @Index var target: String = "", // "contacts", "inbox", etc.
    var cursor: String = "",
    var timestamp: Long = 0
)
```

