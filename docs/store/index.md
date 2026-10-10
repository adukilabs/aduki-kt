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
Android). The SDK additionally seals these sensitive payload columns with
AES-256-GCM ([Vault](../security/vault.md)): `Message.preview`, `Message.blob`,
`Contact.vcard`, `Contact.company`, `Appointment.notes` and the outbox payload.
Names, subjects, addresses, phones, ids, flags, timestamps and every indexed
column stay in the clear so search and sync work. Sealing is tested on a plain
JVM; on Android it is unverified until a device run.

## Pages

- [Entities](entities.md)
- [Indexes](indexes.md)
- [Transactions](transactions.md)
