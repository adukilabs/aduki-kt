# Sealed columns (Vault)

`pro.aduki.crypto.cipher.Vault` seals values with AES-256-GCM ([Envelope](cipher.md))
under keys held by a [Provider](keystore.md). The SDK uses it for the sensitive
payload columns of the local database. It is **not** full-database encryption.

## What is sealed, what is not

Sealed (every personal text column):
- `Message`: `subject`, `fromName`, `fromEmail`, `to`, `preview`, `blob`.
- `Contact`: `name`, `email`, `phone`, `company`, `vcard`.
- `Appointment`: `location`, `notes`. `Mailbox`: `name`. `Service`: `name`, `description`.
- The outbox payload (the content of a message waiting to be sent).

In the clear, because they are structural and sync and queries need them: ids and
`hex` values, UIDs, UIDVALIDITY, MODSEQ and other sequence numbers, flags and
keywords, counts (`exists`, `unseen`, `size`), timestamps, mailbox `role`,
appointment `status`, `method` and times, tenant, host, service and `slug` ids,
`ctag`/etag values and sync tokens. These can reveal when and how much you
use the app, not what was said or to whom.

The database file as a whole is **not** encrypted: ObjectBox 4.0.3 has no
encryption option, and the file relies on the operating system (Android
file-based encryption). Do not describe the SDK as encrypting the database.
Old pages of a database rewritten in place may still hold older values until
the file is compacted.

## Searching sealed columns

- **Exact lookup** (`Contact.byEmail`, `Contact.byPhone`): a keyed blind index.
  The index column holds `<index key id>:<hex HMAC-SHA256>` of the normalised
  value (lower-cased trimmed address; phone reduced to digits and `+`) under a
  per-field key derived by HKDF-Expand from a random seed. The seed lives in
  `aduki-index.keys` next to the database, sealed under the vault, because
  Android Keystore keys cannot be read out to derive from. Equal values give
  equal index values (equality is visible); the value itself is not recoverable
  or guessable without the seed. `vault.rotateIndex()` starts a new index key and
  `Sealing.reseal(store)` rebuilds every index under it.
- **Free text** (`Contact.search`, sorting by name): done in memory over decrypted
  rows. Cost: every contact is read from the database and opened on each
  change of the address book, linear in its size. Nothing searchable is
  persisted in the clear.
- Without a vault (or before a store is opened) `byEmail`/`byPhone` scan the rows.

## Format and rotation

`0xAD 0x4B | version (1) | key id (4) | IV (12) | ciphertext | tag (16)`; text is
stored as `aduki:1:` plus base64. Key id `n` is the Provider alias
`aduki_data_n`. `rotate()` creates a new key and makes it current; values sealed
under older keys still open while those keys exist, and every rewrite seals
under the current key. `Sealing.reseal(store)` rewrites all rows and rebuilds the indexes. A tampered
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
