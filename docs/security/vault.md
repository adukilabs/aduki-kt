# Sealed columns (Vault)

`pro.aduki.crypto.cipher.Vault` seals values with AES-256-GCM ([Envelope](cipher.md))
under keys held by a [Provider](keystore.md). The SDK uses it for the sensitive
payload columns of the local database. It is **not** full-database encryption.

## What is sealed, what is not

Sealed: `Message.preview`, `Message.blob`, `Contact.vcard`, `Contact.company`,
`Appointment.notes`, and the outbox payload (the content of a message waiting to
be sent).

In the clear: names, subjects, sender and recipient addresses, phones, ids
(`hex`), UIDs, flags, thread ids, timestamps, appointment times and status, and
every indexed column. Search and sync need them. The database file as a whole
relies on the operating system (Android file-based encryption); ObjectBox 4.0.3
has no encryption option. Old pages of a database that was migrated from
plaintext may still hold plaintext until the file is compacted.

## Format and rotation

`0xAD 0x4B | version (1) | key id (4) | IV (12) | ciphertext | tag (16)`; text is
stored as `aduki:1:` plus base64. Key id `n` is the Provider alias
`aduki_data_n`. `rotate()` creates a new key and makes it current; values sealed
under older keys still open while those keys exist, and every rewrite seals
under the current key. `Sealing.reseal(store)` rewrites all rows. A tampered
value, an unknown key id or an unknown version throws `SealException`; the SDK
never returns damaged data. Values not in this format are read as legacy
plaintext and sealed when next written.

## Turning it on

```kotlin
val client = Aduki.builder().key(apiKey).secureStore(Provider()).build()
val store = Factory.build(dir)   // or Factory.build(dir, Vault(Provider()))
```

On Android, `Vault.platform()` (the Android Keystore `Provider`) is used when
`secureStore` is not called. On a plain JVM nothing is sealed unless you ask,
and a `Provider` there keeps keys in memory only, so data written by one process
cannot be read by the next: use it for tests. The vault is process-wide and must
be installed before the database is read; with none installed, sealed values
cannot be read.

## Verified and not verified

Tested on the JVM with the software Provider: round trip, the database file
holds no plaintext of a sealed column, tampering and unknown keys fail, rotation
reads old keys, legacy plaintext rows upgrade. **Not verified: the Android
Keystore** (key creation, use with the supplied IV, non-exportability, reinstall
and backup behaviour). Do not rely on it on a device until it has been run there.
