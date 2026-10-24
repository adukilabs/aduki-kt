# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (`AI/SKILLS/release.md`).

## Unreleased

Local store wiring and mailbox sync (#43, #44, #30):

- `Aduki.Builder.store(BoxStore)` and `store(File)`: one call builds the outbox `Manager` and `Worker` (with `HttpDispatcher`), the mail, contact and appointment repositories, and the mailbox, contact and schedule engines on the client's own HTTP clients. Parts passed to the builder still win. `store(File)` opens the database in `build()`; `Aduki.close()` closes it.
- `Aduki.Builder.rejected(callback)`: told about each outbox action the server refused for good.
- `Aduki.schedulingApi`: the scheduling client on the authenticated connection.
- `Mail.send`, `flag`, `move` and `remove` throw `IllegalStateException("no local store")` instead of doing nothing.
- `Sync.mailboxes()` loads `GET /user/mailbox` into the store, keeping the sync cursor of known mailboxes and dropping deleted ones with their messages. `engine.Mailbox.refresh()`, `MailboxDirectory`, implemented by `HttpMailboxTransport`.
- `Sync.all()` with no argument syncs every mailbox (it synced only a mailbox with the hex `inbox`, which never exists). Listed mailboxes may be roles (`inbox`, `sent`, ...). Breaking: the parameter is now `List<String>?` with default `null`.
- `Sync.state: StateFlow<SyncState>` (`running`, `synced`, `error`) and `Sync.outbox: StateFlow<Int>`; `Manager.count`, `Manager.recount()`, `Manager.message(hex)`.
- Live tests take `ADUKI_TOKEN` (an Aduki ID access token) as well as `ADUKI_KEY`, use a local store, and send to `ADUKI_ADDRESS` instead of a fixed external address.

Mail (#24, #25, #26, #27, #28, #29, #31):

- `Aduki.jmap`: a JMAP client for the mail host (`net.http.Jmap`: session, batched calls, blob upload and download). JMAP ids are the REST hexes.
- Bodies and attachments: `Mail.body`, `attachments`, `open`, `save`. Bodies are cached in the new sealed entity `Content` (schema change: a new entity, no migration needed).
- Search: `Mail.search` (offline, in memory over sealed columns) and `Mail.find` (`GET /user/mail/search/{q}`); `net.Mail.search`.
- Threads: `Mail.thread` and `Mail.conversations` (`state.repository.Conversation`).
- Flags: `Mail.setFlag` (idempotent), `setFlags`, `markAllRead`, `move(list)`, `remove(list)`. The bulk forms are one outbox action each (`flags`, `moves`, `deletes`), sent as one JMAP `Email/set` per 500 messages.
- Mailboxes: `Mail.createMailbox`, `renameMailbox`, `deleteMailbox` (optimistic; offline creates adopt the server hex via `Manager.adopt`); `net.Mail.createMailbox`, `renameMailbox`, `deleteMailbox`.
- Compose: `Draft` and `Upload` (core models). `Mail.send(draft)` sends bcc, HTML, attachments, display names, replies and forwards over JMAP (`sync.http.Composer`), retry-safe by Message-ID.
- Snooze: `Mail.snooze`, `unsnooze`, `wake`, and `Message.snoozed()`; `Sync.all` wakes due messages.
- Fixed: `Worker.drain` dispatched from a snapshot, so an action rewritten by an earlier one in the same drain went out stale.
- `HttpDispatcher` maps JMAP method errors other than server failures to `Rejected`.

### Contacts (#36, #37, #38, #39)

- `Contacts.create(card)`, `update(hex, card)` and `delete(hex)`: applied to the local store at once and queued in the outbox (`contact.create`, `contact.update`, `contact.delete`) with a stable `Idempotency-Key`; a created contact's placeholder hex is replaced by the server's and later queued writes follow it. `Contacts.card(hex)` gives the editable fields. `Card` (`pro.aduki.core.models`) holds name, e-mails, phones, company, notes, photo, groups, UID and the source vCard, with `validate()`.
- Groups (`Contacts.groups`): `observe`, `list`, `create`, `rename`, `delete`, `add`, `remove`, and `Contacts.observe(group)`. Groups are the server's per-contact tags; `rename` and `delete` use the proposed `PATCH`/`DELETE /user/contacts/group/{group}` and fall back to per-member writes while the server lacks them.
- vCard: `Contacts.import(InputStream): Imported` (created, updated, skipped, errors) and `export(hexes, version)` to a `String` or an `OutputStream`, vCard 3.0 and 4.0, keeping multi-value fields, their parameters, the photo and unmodelled properties. Codec: `pro.aduki.core.vcard.Vcard`.
- `Contacts.duplicates(): List<Duplicate>` (match on e-mail, phone or name) and `merge(keep, others)`, which combines fields and groups and deletes the others in one local transaction.
- `net.Contacts`: `create`, `replace`, `groups`, `delete`, `members`, `rename`, `dissolve`; `list` takes a `page` and rows carry `uid`, `vcard` and `updated`. Uploads leave out the vCard's `VERSION` line, which the server would otherwise refuse.
- `ContactDispatcher` (chained as `HttpDispatcher`'s fallback by `Builder.store`, with its own `fallback`) and `outbox.Book`; `Manager.repoint`, `drop` and `entry`; the worker re-reads each queued action before sending it.
- Contact sync keeps every e-mail address and phone, reads company, notes and photo from the vCard, follows the server's page mode (it ignored the cursor, so books over 200 contacts were cut short), sees group changes, and leaves contacts with queued writes alone.
- `Contact` entity: `uid`, `emails`, `phones`, `groups`, `notes`, `photo` (sealed except `uid`); the ObjectBox model changed.

## 0.4.1-beta (2026-10-16)

Staged personal account signup (ADK-AUTH-007):

- `Requirements`: dynamic discovery of signup requirements (`GET /v1/accounts`) returning `phone` verification requirement flag and platform domain. Clients dynamically check `Requirements.phone` before prompting for phone number, allowing server configuration changes without client app updates.
- `Enrolment`: account initiation outcome returning pending account ID, canonical handle, TOTP enrolment URI, and base32 shared secret.
- `Signup`:
  - `Signup.requirements(client, identity): Requirements`
  - `Signup.create(client, identity, handle, name, password, phone?): Enrolment`
  - `Signup.verify(client, identity, hex, password, code): List<String>` (returns one-time backup codes)
- `Id`: delegation helpers `requirements()`, `create(...)`, `verify(...)`.
- `Aduki.Companion`:
  - `Aduki.requirements(identity, pins): Requirements`
  - `Aduki.signUp(handle, name, password, phone, identity, pins): Enrolment`
  - `Aduki.verify(hex, password, code, identity, pins): List<String>`
  - `Aduki.register(handle, name, password, code, hex, endpoint, identity, backup, dpop, pins): Pair<Aduki, List<String>>`
- Unit and contract tests with MockWebServer covering discovery, initiation, verification, error mapping, and end-to-end registration.

## 0.4.0 (2026-10-15)

ES256 device credentials (Aduki ID PR 35):

- `Center.register(..., key: Key, ...)` and `Center.unlock(..., key: Key, ...)`: Ed25519 or ES256 from the key type; `crypto.dpop.Device` (algorithm, 65-byte uncompressed P-256 point, `0x04 || x || y`, base64url without padding). `unlock` refuses a signature that is not 64 bytes before sending, treats the uniform 401 as final (no retry) and never reuses a nonce. Fake id server tests with an independent java.security verifier, high-S and low-S, tampered, wrong-key, expired and replayed cases.
- Android, UNVERIFIED (not compiled: no SDK where it was written): `Accounts.registerDevice`, `Accounts.unlockWithDevice`, `AndroidKey.DEVICE`, `AndroidKey.devicePublic`, a credential id in `CenterStore`, sample buttons 9 and 10. The known gap "Keystore key cannot register for center unlock" is closed on the SDK side only.

Android (module `android`, opt-in with `-Paduki.android=true`; `minSdk` 26):

- `AndroidKey` (K5): non-extractable P-256 Keystore key for DPoP (`ES256`); `crypto.dpop.Der` converts the Keystore's DER signature to JWS `r || s`; `Software.jwkOf`.
- Account Center authenticator (K4): `Accounts` (`AccountManager` entries under `pro.aduki.account`, sealed tokens, reconcile with the center, `links = deny` removal), `AdukiAuthenticator`, `SwitcherState`.
- Custom Tabs sign-in (K7): `SignIn`, `PendingStore`, `RedirectActivity`.
- Credential Manager (K6): `CredentialManagerAuthenticator`, `PasskeyFlow`, `PasskeyFailure`.
- Build: Gradle wrapper 9.5.1 (AGP 8.13 does not run on 9.6), a `sample` app for manual tests, `guide/ANDROID-TESTS.md`. Robolectric tests run in `./gradlew test -Paduki.android=true`; Keystore tests ran on an API 34 emulator (software-backed keys). Hardware Keystore, StrongBox, real Credential Manager and the real Aduki ID are not verified.

Schema changed: personal text columns are now sealed and `Contact` gained two index columns, so **an existing local database from 0.3.x must be deleted** (there is no migration; no real data existed). The old plaintext read path is kept only so rows written without a vault still read.

Added:

- Sealed columns: `Vault` (AES-256-GCM, versioned format with a key id for rotation) and `Sealing`. Sealed when a vault is installed: message subject, sender name and address, recipients, preview, blob; contact name, e-mail, phone, company, vCard; appointment location and notes; mailbox and service names and descriptions; the outbox payload. `@Index` was removed from `Contact.name` and `Contact.email`; lists sort in memory. Contacts carry keyed blind indexes (`emailIndex`, `phoneIndex`, HMAC-SHA256 under a per-field key from a sealed seed) for `Contact.byEmail` and `byPhone`; free-text search stays in memory over decrypted rows (`Aduki.Builder.secureStore(provider)`, `Factory.build(dir, vault)`, the Keystore provider by default on Android). Ids, flags, keywords, timestamps, counters, roles and statuses stay in the clear; the database file as a whole is not encrypted. Android Keystore behaviour is unverified.
- Device authorization grant client (RFC 8628) in `Oidc`: `startDevice`, `pollDevice`, `awaitDevice` (polling, `slow_down`, expiry, error mapping); `AdukiException.Denied` and `AdukiException.Expired`; `Oidc.Discovery.device`. Fake-server tests only; never run against Aduki ID.
- `Envelope.encrypt` uses the IV the cipher generates (stored in front of the ciphertext, same layout) and `Provider` no longer calls `setRandomizedEncryptionRequired(false)` by reflection: Keystore keys keep randomized encryption. Old blobs decrypt unchanged. JVM tests with a Keystore-like provider; device run pending.
- `net.http.Passkeys` (K6 protocol part): begin and finish for passkey registration, step-up and sign-in, list, remove, credential shaping and validation. Fake-server tests only; the Credential Manager step is Android and not built.
- `Center.revoke` (device credential revocation) and the first tests of the `Center` client (link, fetch, unlink, register, unlock with a verified Ed25519 signature over the raw nonce, error mapping, no center data in access tokens). Fake server only.
- `net.http.Space` (Space REST client on the JVM): DPoP-bound `space` audience tokens through `Id`, envelope and `AdukiException.Api` (kind, details, `Retry-After`), keyset lists, `Idempotency-Key`, `ETag` / `If-Match`, helpers for tasks, check-ins, tickets, search and settings; `Endpoints.SPACE`, `Endpoints.SPACE_AUDIENCE`. Fake-server tests only; not wired into `Aduki`.
- `HttpContactTransport`, `net.http.Contacts`, `Builder.contactStorage`.

Breaking:

- The gRPC host is dropped from the platform (D-HOST-5). Removed `Endpoints.GRPC_HOST`, `Endpoints.GRPC_PORT`, `Options.grpcHost`, `Options.grpcPort`, `Aduki.Builder.grpc(...)`, `pro.aduki.net.grpc.Channel`, `pro.aduki.net.grpc.Credentials`, and the grpc dependencies.
- TLS pinning is opt-in. The built-in pins (`Pinning.PIN_PRIMARY`, `PIN_BACKUP`, `REST_HOST`, `GRPC_HOST`, `Pinning.pinner()`) are removed: they were never verified against a live host. Pass your own with `Aduki.builder().pins(Pin(host, hash, backup))` (`Options.pins`); with none, the default client does no pinning.
- `Factory.create(dir, key)` lost its ignored `key` parameter (now `create(dir, vault)`).
- `Options.maxRetries` was removed (it was read by nothing).
- The `Authorization` scheme no longer depends on the token prefix: a credential given to `Builder.key(...)` is sent as `Key`, everything else as `Bearer`. A key given through `token(...)` is now `Bearer`.
