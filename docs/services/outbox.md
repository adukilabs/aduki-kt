# Offline Outbox & Retry Engine Reference

The Aduki Android SDK guarantees zero data loss across network drops, crashes, and device reboots via an atomic write-ahead transactional outbox journal.

---

## 1. Outbox Architecture & Execution Loop

```mermaid
sequenceDiagram
    autonumber
    participant App as Client Call (send, flag, move, remove)
    participant Store as ObjectBox Store
    participant Worker as Outbox Worker
    participant Server as Aduki REST API

    App->>Store: Atomic Tx: Write Message (dirty) + Insert Outbox entry
    Store-->>App: Return optimistic Message
    App->>Worker: drain()
    activate Worker
    Worker->>Store: Next pending entry (oldest first)
    Worker->>Server: HttpDispatcher: REST call (send carries Idempotency-Key)
    alt Transient failure (network, 5xx, 408, 409, 429)
        Worker->>Store: attempts++, nextRetry = now + jitter
        Worker->>Worker: Stop: later entries wait (order is preserved)
    else Rejected for good (other 4xx)
        Worker->>Store: Drop entry, clear dirty (next sync restores server state)
        Worker->>App: onRejected(entry, error)
    else Success
        Worker->>Store: Delete entry, clear dirty (send: adopt the server id)
    end
    deactivate Worker
```

---

## 2. Core Method Signatures

### `Manager`

```kotlin
package pro.aduki.sync.outbox

class Manager(storage: Storage) {
    constructor(store: BoxStore)

    fun send(msg: Message, raw: ByteArray): Outbox   // raw = JSON send request
    fun flag(hex: String, flag: Int): Outbox          // toggles locally, journals the result
    fun move(hex: String, dest: String): Outbox
    fun remove(hex: String): Outbox
    fun enqueue(action: String, payload: ByteArray, hex: String = ""): Outbox
    fun pending(): List<Outbox>
    fun complete(id: Long, hex: String? = null)
    fun rename(old: String, new: String)              // placeholder id -> server id
    fun fail(id: Long, delay: Long)
}
```

### `Worker` and `Dispatcher`

```kotlin
fun interface Dispatcher {
    suspend fun dispatch(action: Outbox)
}

class Rejected(message: String, val code: Int? = null, cause: Throwable? = null) : Exception(message, cause)

class Worker(
    manager: Manager,
    dispatcher: Dispatcher,
    jitter: Jitter = Jitter(),
    onRejected: (Outbox, Rejected) -> Unit = { _, _ -> }
) {
    suspend fun drain(): Int
}
```

### `HttpDispatcher`

The REST implementation (`pro.aduki.sync.http`):

```kotlin
val manager = Manager(boxStore)
val dispatcher = HttpDispatcher(
    api = client.mailApi,
    lookup = { hex -> messageBox.query(Message_.hex.equal(hex)).build().use { it.findFirst() } },
    rename = manager::rename,
    fallback = null // or a Dispatcher for non-mail actions such as "appointment_cancel"
)
val worker = Worker(manager, dispatcher, onRejected = { entry, e -> /* tell the user */ })
```

---

## 3. Data Model: `Outbox` Entity

```kotlin
@Entity
data class Outbox(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var action: String = "", // "send", "flag", "move", "delete", ...
    var payload: ByteArray = byteArrayOf(),
    @Index var created: Long = System.currentTimeMillis(),
    var attempts: Int = 0,
    var nextRetry: Long = 0
)
```

### Actions, Payloads and Endpoints

| `action` | Payload (UTF-8 JSON) | Endpoint |
| :--- | :--- | :--- |
| `send` | `{"to": [...], "cc": [...], "subject": "...", "text": "...", "from"?: "..."}` | `POST /v1/user/mail/send` |
| `flag` | `{"hex": "...", "flag": 4, "set": true}` — the resulting state, not a toggle | `PATCH /v1/user/mail/{hex}/flags` (`add` or `remove`) |
| `move` | `{"hex": "...", "mailbox": "<mailbox hex>"}` | `PATCH /v1/user/mail/{hex}/mailbox` |
| `delete` | `{"hex": "..."}` | `DELETE /v1/user/mail/{hex}` |

Entries journaled by 0.1.x (`"hex:flag"`, `"hex:dest"`, a bare hex, or the raw body for `send`) are still dispatched.

A `flag`, `move` or `delete` of a message the server no longer has (404) completes: there is nothing left to do.

---

## 4. Idempotency & Replay Protection

A send whose response is lost (timeout, dropped connection) is retried with the same key, and the server replays the first result instead of sending again:

```http
POST /v1/user/mail/send HTTP/1.1
Authorization: Bearer eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9...
Idempotency-Key: outbox-42-1790413340658
Content-Type: application/json

{"to":["bob@example.com"],"cc":[],"subject":"Lunch","text":"Friday?"}
```

The key is `outbox-<entry id>-<created>`, stable across retries and app restarts. While the first attempt is still running, a retry gets `409` and is retried later. After the send, the local placeholder message (`msg_<millis>`) takes the server's id from the response.

---

## 5. Decorrelated Jitter Retry Backoff

When transient errors occur, retry delays are computed using Amazon's **Decorrelated Jitter** algorithm to avoid thundering-herd synchronization across mobile fleets:

$$t_{i+1} = \min(t_{\max}, \text{random}(t_{\min}, 3 \times t_i))$$

```kotlin
package pro.aduki.core.retry

object Jitter {
    fun nextDelay(currentDelayMs: Long, baseMs: Long = 1000L, maxMs: Long = 60000L): Long {
        val high = (currentDelayMs * 3).coerceAtLeast(baseMs)
        val jittered = kotlin.random.Random.nextLong(baseMs, high + 1)
        return jittered.coerceAtMost(maxMs)
    }
}
```

