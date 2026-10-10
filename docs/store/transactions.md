# Transactions

`pro.aduki.store.queries.Batch` runs several writes in one ObjectBox
transaction: they commit together or not at all.

```kotlin
class Batch(private val store: BoxStore) {
    fun <T> tx(block: () -> T): T
    fun run(block: () -> Unit)
}
```

```kotlin
val batch = Batch(boxStore)

val count = batch.tx {
    messageBox.put(newMessages)
    mailbox.modseq = highestModseq
    mailboxBox.put(mailbox)
    outboxBox.remove(actionId)
    newMessages.size
}
```

If the block throws, every write inside it is rolled back.

The sync engines and the outbox use transactions the same way: applying a
page of mailbox changes (messages and the mailbox's `modseq`) is one
transaction, and an outbox action is journaled in the same transaction as the
local change it describes.

ObjectBox serializes write transactions and lets readers run concurrently
with a writer; see the [ObjectBox documentation](https://docs.objectbox.io)
for its transaction semantics.
