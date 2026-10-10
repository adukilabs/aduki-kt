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

The SDK does not encrypt the database, and the ObjectBox library version it
uses (4.0.3) has no encryption option; `Factory.create(dir)` takes no key. Apps
that need encryption at rest must rely on the platform (file-based encryption
on Android) or encrypt sensitive fields themselves with the
[`Envelope`](../security/cipher.md) cipher. Encrypting the SDK's own database
with a Keystore-held key is planned and not built.

## Pages

- [Entities](entities.md)
- [Indexes](indexes.md)
- [Transactions](transactions.md)
