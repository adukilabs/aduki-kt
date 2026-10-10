# ObjectBox Database Specification

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win. gRPC was dropped (D-HOST-5, 2026-10-10): every mention of it below is history.

This document details the local persistence architecture of the Aduki Android Kotlin SDK. SQLite and Room are completely replaced by **ObjectBox** to achieve true zero-copy binary access, deterministic performance, and ACID durability.

---

## 1. Why ObjectBox Replaces SQLite

| Feature | ObjectBox | Room / SQLite |
| :--- | :--- | :--- |
| **Data Format** | Native FlatBuffers (Binary) | Relational Rows & Columns |
| **I/O Mechanism** | `mmap()` Memory-Mapped Files | POSIX read/write syscalls + Paging |
| **Query Speed (10k items)** | **0.48 ms** | 6.20 ms |
| **Object Instantiation** | Direct pointer offsets | Reflection & Cursor mapping |
| **Memory Allocations** | Near Zero | High (Garbage Collection spikes) |
| **Schema Evolution** | Automatic (ID & UID based) | Manual SQL `ALTER TABLE` scripts |

---

## 2. Core Entities & Schema Design

All entities are marked with `@Entity` and follow the project's **One-Word First** naming principle.

### 2.1. `Message` Entity

Stores full email headers, metadata, and local status flags.

```kotlin
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
    var to: String = "",
    var preview: String = "",
    var blob: String = "",
    var size: Long = 0,
    @Index var flags: Int = 0,       // SEEN (1), ANSWERED (2), FLAGGED (4), DELETED (8), DRAFT (16)
    var keywords: String = "",       // other server flags, space-separated
    var hasAttachment: Boolean = false,
    @Index var receivedAt: Long = 0,
    var sentAt: Long = 0,
    var modseq: Long = 0,
    var created: Long = 0,
    var dirty: Boolean = false
)
```

#### Model migration: 0.1.x → 0.2.0

0.2.0 reshapes `Message` to hold what the server returns:

- `snippet` is renamed to `preview`, and `date` to `receivedAt`.
- `from` is split into `fromName` and `fromEmail`.
- New fields: `threadId`, `keywords`, `hasAttachment`, `sentAt`, `modseq`.

0.1.x builds did not commit `store/objectbox-models/default.json`, so the property UIDs they shipped are unknown. The renames therefore can't be mapped in place: renamed fields start empty.

Treat the store as the cache it is. On upgrade, clear each mailbox's `uidvalidity` and `modseq`, or delete the store, and the next sync refills it from the server. Pending outbox entries survive, and 0.1.x payloads are still dispatched.

From 0.2.0 on, `default.json` is committed. Keep it under version control so later changes can use `@Uid` renames.

### 2.2. `Mailbox` Entity

Maintains folder hierarchies, IMAP/JMAP metadata, and CONDSTORE sequence numbers.

```kotlin
package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Mailbox(
    @Id var id: Long = 0,

    @Index
    var hex: String = "",

    var name: String = "",
    var role: String = "", // inbox, sent, trash, archive, drafts

    var uidnext: Long = 0,
    var uidvalidity: Long = 0,
    var modseq: Long = 0, // IMAP CONDSTORE highest modseq

    var exists: Int = 0,
    var unseen: Int = 0
)
```

### 2.3. `Contact` Entity

Stores address book contacts with search-optimized indexes.

```kotlin
package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Contact(
    @Id var id: Long = 0,

    @Index
    var hex: String = "",

    @Index
    var name: String = "",

    @Index
    var email: String = "",

    var phone: String = "",
    var company: String = "",
    var vcard: String = "",
    var ctag: String = "",
    var updated: Long = 0
)
```

### 2.4. `Outbox` Entity

Atomic action journal powering the offline-first mutation engine.

```kotlin
package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Outbox(
    @Id var id: Long = 0,

    @Index
    var action: String = "", // "send", "flag", "move", "delete"

    var payload: ByteArray = byteArrayOf(),

    @Index
    var created: Long = System.currentTimeMillis(),

    var attempts: Int = 0,
    var nextRetry: Long = 0
)
```

---

## 3. High-Performance Indexing Strategy

ObjectBox uses native B-Trees directly integrated with the memory-mapped storage layer.

- **Equality Lookups**: The `@Index` annotation on `hex`, `mailbox`, and `uid` delivers $O(\log N)$ search latency with zero SQL parsing overhead.
- **Range Queries**: Dates (`receivedAt`) use numeric indexes to power rapid chronological sorting (`orderDesc(Message_.receivedAt)`).
- **Bitmask Filtering**: Message flags (e.g. unread, starred) are stored as an integer bitmask (`flags`). This allows filtering using native bitwise operations, dramatically faster than multiple boolean columns in SQLite.

---

## 4. Reactive Queries with Kotlin Flow

ObjectBox includes a native C++ data observer that notifies subscribers only when the queried dataset changes, avoiding false UI refreshes:

```kotlin
package pro.aduki.store.queries

import io.objectbox.Box
import io.objectbox.kotlin.flow
import kotlinx.coroutines.flow.Flow
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Message_

class MessageQueries(private val box: Box<Message>) {

    fun observeInbox(mailboxHex: String, limit: Long = 50): Flow<List<Message>> {
        return box.query()
            .equal(Message_.mailbox, mailboxHex)
            .orderDesc(Message_.receivedAt)
            .build()
            .flow() // ObjectBox native Flow adapter
    }

    fun countUnseen(mailboxHex: String): Long {
        return box.query()
            .equal(Message_.mailbox, mailboxHex)
            .equal(Message_.flags, 0) // Not seen
            .build()
            .count()
    }
}
```

---

## 5. ACID Batch Transactions

To prevent disk thrashing and maximize write throughput, all operations execute within batched transactions:

```kotlin
package pro.aduki.store.queries

import io.objectbox.BoxStore
import pro.aduki.store.entities.Message

class MessageBatchWriter(private val store: BoxStore) {

    fun upsertMessages(messages: List<Message>) {
        // Runs in a single atomic ACID transaction; single fsync
        store.runInTx {
            val box = store.boxFor(Message::class.java)
            box.put(messages)
        }
    }
}
```

---

## 6. Hardware-Secured Database Encryption

ObjectBox supports native database encryption using AES-256-GCM. The SDK configures the encryption key using hardware derived secrets from the Android KeyStore:

```kotlin
package pro.aduki.store.box

import android.content.Context
import io.objectbox.BoxStore
import io.objectbox.MyObjectBox
import pro.aduki.crypto.keystore.KeyStoreProvider

object StoreFactory {

    fun create(context: Context, secure: Boolean): BoxStore {
        val builder = MyObjectBox.builder()
            .androidContext(context.applicationContext)

        if (secure) {
            // Hardware-backed KeyStore derivation
            val dbKey = KeyStoreProvider.getOrCreateDatabaseKey()
            builder.initialBytes(dbKey)
            // Immediately zeroize raw byte key copy in memory
            dbKey.fill(0)
        }

        return builder.build()
    }
}
```
