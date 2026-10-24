# Sync Service Reference (CONDSTORE / MODSEQ)

The `Sync` service (`client.sync`) orchestrates incremental delta synchronization across email folders and address book contacts using RFC 7162 CONDSTORE / MODSEQ extensions.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Sync internal constructor(...) {
    val state: StateFlow<SyncState>
    val outbox: StateFlow<Int>

    suspend fun mailboxes(): List<String>

    suspend fun all(mailboxes: List<String>? = null): Boolean

    suspend fun flush(): Int

    fun pending(): Int
}

data class SyncState(
    val running: Boolean = false,
    val synced: Long = 0,        // epoch millis of the last clean sync, 0 if none
    val error: Throwable? = null // why the last sync failed; null after a clean one
)
```

Everything below needs a local store (`Aduki.Builder.store`, see [Installation](../start/install.md)). Without one, `all` and `flush` have nothing to do and `outbox` stays 0.

---

## 2. Detailed Method Specifications

### `mailboxes`
Loads the account's mailbox list (`GET /v1/user/mailbox`, every page) into the local store and returns the mailbox hexes.

```kotlin
suspend fun mailboxes(): List<String>
```

- Known mailboxes keep their sync cursor (`uidvalidity`, `modseq`); name, role and counts are updated.
- A mailbox deleted on the server is dropped from the store with its messages.
- `client.mail.mailboxes()` observes the result.

---

### `all`
Executes an incremental delta sync across mailboxes and subsequently syncs contacts for the active tenant.

```kotlin
suspend fun all(mailboxes: List<String>? = null): Boolean
```

#### Parameters

| Parameter | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `mailboxes` | `List<String>?` | No | `null` | `null` loads the mailbox list first (as `mailboxes()`) and syncs every mailbox. Otherwise each entry is a mailbox hex or a role (`inbox`, `sent`, `drafts`, `trash`, `junk`, `archive`); an entry that is not known locally loads the mailbox list once. |

#### Return Value
- **Type**: `Boolean`
- **Description**: `true` if every targeted mailbox and the address book synced; `false` if a listed mailbox does not exist.

#### State and errors
- Calls are serialized: a second `all` waits for the first.
- `state.running` is true while it runs. On success `state.synced` is set and `state.error` cleared.
- A network or server failure is rethrown and kept in `state.error`; `state.synced` keeps the time of the last clean sync.

```kotlin
client.sync.state.collect { s ->
    refreshing = s.running
    s.error?.let { showError(it) }
}
```

---

### `flush`
Immediately drains all queued offline outbox mutations over the active network transport.

```kotlin
suspend fun flush(): Int
```

- **Return Value**: `Int` — Number of outbox mutations successfully processed and drained.
- **Execution**: Invokes `Worker.drain()`. If network is disconnected or the circuit breaker is open, returns early without throwing.

---

### `pending`
Returns the count of unsynced offline mutations currently queued in the local outbox journal.

```kotlin
fun pending(): Int
```

- **Return Value**: `Int` — Count of pending outbox actions (`send`, `flag`, `move`, `remove`).

### `outbox`
The same count as a `StateFlow<Int>`, for a "waiting to send" badge. It changes whenever an action is queued, completed or dropped.

---

## 3. Wire Protocol: `GET /v1/user/mail/changes`

`HttpMailboxTransport` (`pro.aduki.sync.http`) implements `MailboxTransport` over the REST face of CONDSTORE/QRESYNC:

```kotlin
val engine = Mailbox(boxStore, HttpMailboxTransport(client.mailApi))
engine.sync(mailboxHex)
```

### HTTP Request

```http
GET /v1/user/mail/changes?mailbox=M0X1A2B3C4D5E6F&since=11500&uidvalidity=1790413341&limit=500 HTTP/1.1
Authorization: Bearer <access token>
```

| Parameter | Meaning |
| :--- | :--- |
| `mailbox` | Mailbox hex. |
| `since` | The MODSEQ from the previous response; `0` for a first sync. |
| `uidvalidity` | The UIDVALIDITY last seen. Omitted on a first sync. |
| `limit` | Page size (default 500, at most 5000). |

### HTTP Response (200 OK)

```json
{
  "mailbox": "M0X1A2B3C4D5E6F",
  "uidvalidity": 1790413341,
  "modseq": 11556,
  "reset": false,
  "more": false,
  "created": [
    {
      "hex": "M0GDB6EC798DDC2F3460", "uid": 4, "subject": "Lunch on Friday?",
      "sender": "bob@example.com", "size": 2048, "flags": [], "thread": "T0X9F8E7D6C5B4A",
      "spam": null, "internaldate": "2026-09-26T09:02:20.658460",
      "preview": "Are you free for lunch on Friday?",
      "from": {"name": "Bob Example", "email": "bob@example.com"},
      "to": [{"name": "Alice", "email": "alice@aduki.test"}],
      "has_attachment": true, "mailbox": {"hex": "M0X1A2B3C4D5E6F", "name": "INBOX"},
      "total": 0, "modseq": 11556
    }
  ],
  "updated": [{"hex": "M0GE32946DDC56C14639", "uid": 2, "flags": ["\\Seen", "\\Flagged"], "modseq": 11553}],
  "vanished": [3]
}
```

- **`created`**: messages new to this mailbox, including ones moved in from another mailbox (they keep their id; the engine reuses the local row).
- **`updated`**: flag changes on messages the client already has.
- **`vanished`**: UIDs expunged or moved away.
- **`more`**: another page waits past `modseq`. `Mailbox.sync` keeps fetching, committing each page, until it is `false`.
- **`reset`**: the client's UIDVALIDITY is stale. The transport refetches from `since=0` and the engine replaces the local copy.
- Timestamps are UTC without an offset.

---

## 4. Reconcile & Merge Algorithm

```mermaid
flowchart TD
    Start[Receive Mailbox Changes] --> CheckUID{Server uidvalidity == Local uidvalidity?}
    CheckUID -- No --> Invalidate[Wipe local messages for mailbox & full resync]
    CheckUID -- Yes --> Merge[Reconciliation Transaction]
    Merge --> Purge[Delete vanished UIDs]
    Merge --> Update[Upsert modified messages]
    Update --> CheckDirty{Is local message dirty?}
    CheckDirty -- Yes --> Preserve[Preserve local dirty flags & outbox journal]
    CheckDirty -- No --> Apply[Apply server state directly]
    Apply --> StoreModseq[Commit new mailbox.modseq]
    StoreModseq --> End[Finish Atomic Tx]
```

---

## 5. Data Model: `Sync` Cursor Entity

```kotlin
package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Sync(
    @Id var id: Long = 0,
    @Index var target: String = "", // e.g. "contacts", "inbox"
    var token: String = "",
    var timestamp: Long = 0
)
```

