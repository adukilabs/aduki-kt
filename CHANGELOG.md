# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (see `CONTRIBUTING.md`).

## 0.5.3 (2026-10-29)

Release notes:

- Account sessions & device revocation (#86, PR #94): Added `AccountSession` data class, `Aduki.sessions()`, `Aduki.revoke(hex)`, `Id.sessions()`, and `Id.revoke(hex)` for auditing and revoking active device logins and sessions. Refreshed token state is automatically synchronized across active sessions and token stores.
- Mailbox hierarchy & move (#88, PR #93): Added `parent` hierarchy pointer to `Mailbox` entity and `MailboxRow`. Added `Mail.moveMailbox(hex, parent)` with optimistic local ObjectBox store updates and `mailbox.move` sync outbox journaling.
- Mail raw RFC 822 source & headers (#85, PR #93): Added `Mail.headers(hex)` and `Mail.source(hex)` using JMAP `Email/get` properties and authenticated `/jmap/download` endpoint for RFC 822 message inspection.
- Thread muting (#84, PR #93): Added `Message.MUTED` (`$muted`) keyword support, `Message.muted()`, `Conversation.muted`, and `Mail.mute(threadId, on)` outbox flag action.
- Mail drafts (#83, PR #93): Added `Message.PENDING` (`msg_`) local prefix, `Mail.saveDraft(draft)` and `Mail.openDraft(hex)` creating and updating drafts in the local store and syncing via outbox action `draft_save`.
- Vacation responder singleton (#87, PR #92): Wrapped `SINGLETON` ID in a singleton list for `Responder.fetch()` to resolve `NoSuchMethodError` across Android runtimes.
- TokenStore & race recovery (#89, #90, PR #92): Added thread-safe `TokenStore` interface and default implementation for atomic token reloads upon HTTP 401 token expiry; prevented blanking refresh tokens when unauthorized.
- Dependency updates (#91, PR #95): Upgraded `kotlinx.coroutines` to 1.10.1, `org.json` to 20240303, and AndroidX test libraries (`androidx.test.ext:junit` 1.3.0, `androidx.test:core` 1.7.0, `androidx.test:runner` 1.7.0, `androidx.test.espresso` 3.7.0).

## 0.5.2 (2026-10-26)

Release notes:

- Android Digital Asset Links federation (#81): Added default `asset_statements` manifest declaration pointing to `https://mail.aduki.pro/.well-known/assetlinks.json` (`@string/asset_statements`) for Android Credential Manager saved credential federation.
- Transitive Credential Manager dependencies (#81): Exported `androidx.credentials:credentials-play-services-auth` as `api` in `pro.aduki:android` so Android 13 and below consumer apps inherit Play Services fallback automatically.
- Documentation: Documented Digital Asset Links configuration, custom domain merging, and keystore fingerprint retrieval in `android/README.md` and `docs/start/android.md`.
- Automated test coverage: Added Robolectric test verifying merged `asset_statements` manifest meta-data and JSON structure.

## 0.5.1 (2026-10-26)

Release notes:

- Multi-channel publishing: Automated release pipeline to Maven Central, GitHub Packages, and JitPack (with JDK 17 and Android library packaging via `jitpack.yml`).
- Scheduling recurrence overrides & splitting (#60, PR #79): `updateOccurrence(hex, start, changes, notify)` writing `RECURRENCE-ID` overrides, `cancelOccurrence(hex, start, notify)` appending `EXDATE`, `updateFollowing(...)` splitting series with `UNTIL`, and overlaying overrides in `expand` and `observe`.
- Mail header fields (#68, PR #78): Expose `cc`, `bcc`, `replyTo`, and `messageId` on `MessageRow`, `parseRow`, `Message` entity (sealed text), `ccList()`, `bccList()`, `replyToList()`, and sync transport mapping.
- Android multi-app account sharing (#66, PR #76): `Accounts.share(account, targetPackage)` and cross-application visibility.
- Android Account Center unlock (#65, PR #77): Verification of device unlock flow with ES256 challenge.
- Contacts (#67, PR #75, #64, PR #69): vCard 2.1 export compatibility with `N` fallback; exhaustive multi-field contact search over sealed columns.
- Outbox (#58, PR #74): Keep permanently refused actions as failed entries.
- Search pull (#59, PR #73): Pull search hits into the local ObjectBox store.
- Scheduling offers & services (#61, PR #71, #62, PR #70, #63, PR #72): Weekly hours, calendar listing, offers CRUD, and booking service operations.
- Publishing documentation: Added `PUBLISH.md` guide and consumer setup instructions.

## 0.5.0 (2026-10-25)

Release notes:

- `pro.aduki:android` is published to Maven Central and GitHub Packages for the first time, with sources and javadoc. The publish workflow now builds with the Android SDK.
- Single-row ObjectBox lookups close their queries (no more cursors closed from the finalizer thread).
- Scheduling: `updateOccurrence(hex, start, changes, notify)` writing `RECURRENCE-ID` overrides, `cancelOccurrence(hex, start, notify)` appending `EXDATE`, `updateFollowing(...)` splitting series with `UNTIL`, and overlaying overrides in `expand` and `observe` (#60).
- Mail: expose `cc`, `bcc`, `replyTo`, and `messageId` on `MessageRow`, `parseRow`, `Message` entity (sealed text), `ccList()`, `bccList()`, `replyToList()`, and sync transport mapping (#68).
- Server requirements: mail `0.1.0-7af81dac` or later for DPoP-bound sessions, default sender, empty contact groups, contact merge and import, invitation replies, page stats and native FCM push. Against an older server, those calls answer 404 or 405.

Server follow-ups (mail release 0.1.0-7af81dac; #34, #35, #37, #38, #39, #40, #41):

- Contacts: uploads keep their `VERSION:4.0` line (the `Contacts.bare` workaround is gone). `net.Contacts.groups()` (`GET /user/contacts/groups`, `GroupRow(name, count)`), `createGroup(name)`, `merge(hex, others, vcard?)` and `import(vcf)` (`ImportReport`); `rename` and `dissolve` return how many contacts changed. Breaking: `Contacts.bare` is removed; `rename` and `dissolve` return `Int`.
- Groups: `Groups.create(group, members = emptyList())` makes a group with no members, kept as the new sealed `Group` entity (schema change: a new entity, no migration needed) and queued as `contact.group` (`POST /user/contacts/groups`). `Groups.list()` and `observe()` include the server's empty groups, read on every contact sync. Rename and delete use the group routes directly, with no per-member fallback.
- `Contacts.merge` queues one `contact.merge`, sent as the atomic `POST /user/contacts/{hex}/merge` (it was an update plus a delete per other contact); a 404 falls back to the separate calls.
- `Contacts.import` without a local store sends the file to `POST /user/contacts/import`; with a store it still runs on the device (offline, matching by UID and then e-mail).
- The contact sync token is a digest of `hex:etag` only (the etag now covers groups), and the list is walked with the `after` cursor.
- Scheduling: `respond(hex, response, address?, comment)` sends the comment with `POST /user/events/{hex}/reply`; an event the server will not answer for (422) is stored with `PATCH` instead. `create`, `update`, `reschedule`, `cancel` and `remove` take `notify` (`"notify": true`, `DELETE ...?notify=true`) to send attendees iTIP messages. `stats` reads `GET /user/pages/{hex}/stats` only (no device-side count). Breaking: `net.Scheduling.reply` and `stats` no longer return null.
- Settings: `isDefault` and `prefer` work on the server; a JMAP-composed draft without `from` uses the default sender. Push `StateChange.mailboxes` is sent, so a push syncs only the named mailboxes. `Subscription(fcm = token)` sends `fcmToken` alone (no `url`, no `keys`); `Settings.receive(data: Map<String, String>)` and `receive(payload)` read an FCM data message's `push` entry. `docs/services/settings.md` shows the `FirebaseMessagingService` wiring.
- Live tests: an empty group, a contact merge, `isDefault`, and an FCM registration.

Scheduling (#40, #41, #42, #48):

The 0.4 scheduling client called `/user/appointments`, `/user/services`, `/user/availability` and `/book/{slug}`, which do not exist on the server (404). It now uses events, booking pages, schedules and bookings.

- Events (`/user/events`) are iCalendar objects. `Scheduling.create(EventRequest)` makes personal, all-day and repeating events with attendees and reminders; `update(hex, EventChanges)`, `reschedule(hex, start, end)`, `respond(hex, Response.Accepted | Declined | Tentative, address?)`, `cancel(hex, reason)` (`STATUS:CANCELLED`) and `remove(hex)`. All are optimistic in the store and queued in the outbox (`event_create`, `event_update`, `event_reply`, `event_delete`), sent by the new `sync.http.ScheduleDispatcher`, which the store wiring chains after `HttpDispatcher` (a list of fallbacks in `client.kt`). Writes without a store throw `IllegalStateException("no local store")`. An event made offline has a `local-...` hex until it is sent.
- `observe(start, end): StateFlow<List<Occurrence>>` and `expand(start, end)`: occurrences in a range, repeats expanded on the device (`core.calendar.Recurrence`: RRULE with FREQ, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH, BYSETPOS, less EXDATE, in the event's zone).
- `importIcs(text)` and `exportIcs(hexes?)`: iCalendar files read and written on the device (`core.calendar.Ics`, `core.calendar.Events`), one event per UID; a known UID is replaced.
- Services are booking pages: `services()`, `service(hex)`, `createService(ServiceRequest)`, `updateService(hex, ServiceRequest)` (`paused = true` pauses), `removeService(hex)`, with weekly availability `Window`s kept in a schedule of the service's own; `bookingUrl(service)`; `offers(service)` (`/user/schedule/services`); `slots(service, start, end, length?)`; `book(service, BookRequest)` as a guest and `reserve(service, BookRequest)` as the account; `bookings()`, `booking(hex)`, `updateBooking(hex, state)`; `stats(service, start, end)`.
- `respond` and `stats` try the proposed `POST /user/events/{hex}/reply` and `GET /user/pages/{hex}/stats` first, and fall back (the answer stored in the account's copy only; bookings counted on the device for the whole account) while the server lacks them.
- `Identity.email` (from `GET /user`).
- Breaking: `net.http.Scheduling` lost `appointments`, `appointment`, `create(service, ...)`, `cancel`, `availability` and `book(slug, ...)`; `sdk.Scheduling.create`, `cancel`, `slots` and `book` take the new types; `AppointmentRecord` and `ServiceRecord` follow the server's event and page; `sync.engine.Schedule` takes the outbox `Manager`. `Scheduling.cancel` no longer also calls the network itself (it cancelled twice), and the cancel is journaled through the manager, sealed, so `Sync.outbox` counts it. A 0.4 `appointment_cancel` still in an outbox is sent as an update.
- Schema: `Appointment` gained `calendar`, `allday`, `title`, `organizer`, `attendees`, `rrule`, `exdate`, `reminders`, `ical`, `etag` and `moved` and lost `host` and `rescheduled`; title, organizer, attendees and the iCalendar object are sealed.

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
- Android: `Accounts.registerDevice`, `Accounts.unlockWithDevice`, `Accounts.revokeDevice`, `AndroidKey.DEVICE`, `AndroidKey.devicePublic`, credential management in `CenterStore`, and 404/revocation handling verified with tests. Multi-app per-app DPoP session sharing recipe documented in `docs/start/android.md`.

Android (module `android`, opt-in with `-Paduki.android=true`; `minSdk` 26):

- `AndroidKey` (K5): non-extractable P-256 Keystore key for DPoP (`ES256`); `crypto.dpop.Der` converts the Keystore's DER signature to JWS `r || s`; `Software.jwkOf`.
- Account Center authenticator (K4): `Accounts` (`AccountManager` entries under `pro.aduki.account`, sealed tokens, reconcile with the center, `links = deny` removal), `AdukiAuthenticator`, `SwitcherState`.
- Custom Tabs sign-in (K7): `SignIn`, `PendingStore`, `RedirectActivity`.
- Credential Manager (K6): `CredentialManagerAuthenticator`, `PasskeyFlow`, `PasskeyFailure`.
- Build: Gradle wrapper 9.5.1 (AGP 8.13 does not run on 9.6), a `sample` app for manual tests. Robolectric tests run in `./gradlew test -Paduki.android=true`; Keystore tests ran on an API 34 emulator (software-backed keys). Hardware Keystore, StrongBox, real Credential Manager and the real Aduki ID are not verified.

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
