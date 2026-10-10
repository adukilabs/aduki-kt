# Storage

Local data is stored with [ObjectBox](https://objectbox.io) 4.0.3. The `store`
module holds the entities, a factory that opens a `BoxStore`, and a `Batch`
helper for transactions. The sync engines, the outbox and the repositories
read and write through it.

```kotlin
val store: BoxStore = Factory.build(context.filesDir)   // pro.aduki.store.box.Factory
```

`Factory.create(dir)` returns a `BoxStoreBuilder` for the directory;
`Factory.build(dir)` builds it.

## Encryption

The database file as a whole is **not** encrypted: ObjectBox 4.0.3 has no
encryption option, so the file relies on the platform (file-based encryption on
Android). The SDK additionally seals every personal text column with
AES-256-GCM ([Vault](../security/vault.md)): message subject, sender, recipients,
preview and blob; contact name, e-mail, phone, company and vCard; appointment
location and notes; mailbox and service names; the outbox payload. Ids, flags,
timestamps, counters and roles stay in the clear so sync works; contact lookups
use keyed blind indexes and free-text search runs in memory. Sealing is tested on a plain
JVM; on Android it is unverified until a device run.

## Pages

- [Entities](entities.md)
- [Indexes](indexes.md)
- [Transactions](transactions.md)
