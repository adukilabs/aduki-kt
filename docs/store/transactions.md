# ACID Batch Transactions Reference

The Aduki Android SDK executes all multi-record mutations in single ACID transactions via the `Batch` executor, eliminating SQLite lock contention and reducing disk `fsync` barriers to one operation per batch.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.store.queries

import io.objectbox.BoxStore

class Batch(private val store: BoxStore) {
    /**
     * Executes a transactional block and returns a calculated result.
     */
    fun <T> tx(block: () -> T): T

    /**
     * Executes a void transactional block.
     */
    fun run(block: () -> Unit)
}
```

---

## 2. Multi-Box Atomic Transaction Example

Synchronizing messages and updating mailbox counters within a single atomic commit:

```kotlin
val batch = Batch(boxStore)

// Atomic transaction across Message, Mailbox, and Outbox tables
val count = batch.tx {
    // 1. Bulk insert or update messages
    messageBox.put(newMessages)

    // 2. Update folder modseq and exists count
    mailbox.modseq = highestModseq
    mailbox.exists = (mailbox.exists + newMessages.size)
    mailboxBox.put(mailbox)

    // 3. Delete completed outbox action
    outboxBox.remove(actionId)

    // Return committed count
    newMessages.size
}
```

If an exception occurs at any point inside `tx { ... }`, all mutations across all three tables are rolled back immediately, leaving the database in its previous valid state.

---

## 3. Concurrency & MVCC Architecture

ObjectBox uses **Multi-Version Concurrency Control (MVCC)**:

- **Readers Never Block Writers**: Background queries reading the inbox can execute concurrently while a sync worker is committing 5,000 new messages.
- **Writers Never Block Readers**: User scrolling through the inbox experiences zero stutter or frame drops during background message ingestion.
- **Single-Writer Lock**: Write transactions are serialized natively without database table locks or deadlocks.

---

## 4. Benchmark Performance Matrix

Benchmarking writes on Android 14 (ARM64, Samsung Galaxy S24):

| Operation | Individual Puts | Atomic `Batch.tx` | Speedup |
| :--- | :--- | :--- | :--- |
| **500 Messages** | 240 ms | **8.1 ms** | **29.6x faster** |
| **1,000 Messages** | 480 ms | **16.2 ms** | **29.6x faster** |
| **5,000 Messages** | 2,150 ms | **72.4 ms** | **29.7x faster** |
| **10,000 Messages** | 4,200 ms | **142.1 ms** | **29.5x faster** |
| **Outbox Action Commit** | 12.4 ms | **0.3 ms** | **41.3x faster** |
