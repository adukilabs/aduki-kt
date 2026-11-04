# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (see `CONTRIBUTING.md`).

## 0.8.0 (2026-11-04)

The suite sign-in protocol: one account directory per device, per-app sessions opened on demand from a signed-in sibling, and the Accounts Center. It needs Aduki ID with handoff `jkt`/`expires_in`, device groups, `GET /v1/me`, link tickets, the rich `GET /v1/center` and DPoP nonces (adukilabs/id#62). This is a minor release because sign-in, token clearing and reconcile change in substance. Source breaks: a new `AdukiException.Proof` subclass (an exhaustive `when` needs a branch), `pro.aduki:android` now depends on `pro.aduki:sdk`, and the old account calls are removed (see Removed below). See `docs/auth/suite.md`.

Sessions are no longer wiped by mistake:

- 401s are typed by Aduki ID's kind. `AdukiException.Unauthorized` gains `kind` and `ended`; `ended` is true for `auth.invalid`, `auth.reused`, `auth.revoked`, `auth.expired` only (a 401 with no kind, e.g. from a proxy, keeps the tokens). The new `AdukiException.Proof(kind, nonce)` covers `auth.dpop`, and `auth.nonce` after the one retry. The SDK drops tokens only when `ended` is true. Before, every 401 cleared the store, a refused DPoP proof (a clock off by more than 60 s, a Keystore hiccup) included. `Login.logout` keeps the token unless the session ended.
- DPoP nonces (RFC 9449 section 8). The `DPoP-Nonce` of every answer is kept per origin, shared by every `Dpop` in the process, and put in the next proofs. Only a nonce challenge is retried, once: `401 auth.nonce`, OAuth `400 use_dpop_nonce`, or a `WWW-Authenticate` naming it. Before, any 401 or 400 carrying a nonce was resent, a refused refresh included.
- `Dpop.lazy(source)` and `Aduki.Builder.dpop { key }` read the key at the first proof, and again while that fails, instead of once at build.
- Rotated tokens are saved with the new `TokenStore.saveNow` (the default calls `save`) before they are used. `Accounts.save` (`Mode.Own`) and `Accounts.discard` write with `commit()`, not `apply()`.
- Renewals are serialised by a process-wide lock per Aduki ID session, across `Aduki` instances: two clients of one session in one process rotate it once.
- `Handoff.expires` is on the phone's clock: Aduki ID's `expires_in` is added to the local time. Before, the server's absolute time was compared with a clock that may be wrong.

New API:

- `Aduki.account(refresh)`: `GET /v1/me` as an `AccountProfile`, cached. It has the real hex, handle, name, kind, tenant (`Tenancy`: hex, name, role, owner), photo tag, center, unlock, session and device group.
- `Aduki.center()`: a `CenterView` with every account's profile. A lone account comes back with `center` null.
- `Aduki.link(ticket)`, `unlink(code)` and `handoff(target, ttl, jkt)`; `ttl` now accepts 30 to 86400 seconds.
- `Aduki.endDevice()` (`DELETE /v1/sessions?group=`; false, tokens kept, when the group is unknown) and `removeFromDevice(account, group)` (`&account=`).
- `Aduki.signIn(..., link)` and `Aduki.exchange(handoff, ...)` return `Opened(client, group, ticket)`.
- Net: `Login.signIn(..., link)` and `Login.open(handoff)` return `SignedIn(tokens, group, ticket)`, and `Passkeys.signIn(..., link)` does the same. Also `Id.me`, `Id.center`, `Id.link`, `Id.unlink`, `Id.endGroup` and `Id.handoff(target, ttl, jkt)`, plus `Center.view` and `Center.link(..., ticket)`.
- New models in `pro.aduki.core.models`: `AccountProfile`, `Tenancy`, `CenterView` and `SignedIn`.
- Android, `AccountDirectory`: the device's accounts as version 2 `AccountManager` entries with the full profile, device group and link state (`linked`, `removedBy`, `refusal`), plus a private mirror (`orphans`, `restore`) and `changes()`. Its `reconcile(view, current)` never drops an account; device-only accounts are kept with the reason. `migrate(profile)` moves an entry keyed by email to the real hex in place and moves this app's tokens with it. It returns the move, so the app can re-key its own settings (e.g. `contacts.phonebook`).
- Android, the broker: `SuiteBrokerService` (provider) is an exported bound service behind the signature permission `pro.aduki.permission.ACCOUNT_ACCESS`. Every call is checked with `Callers`: the caller's package must be in the suite and pass `checkSignatures == SIGNATURE_MATCH`, with no debug bypass. `SuiteBroker.open(hex, key) { code -> ... }` (caller) asks each signed-in sibling for a code bound to its key (ttl 120) and redeems it. When none can help it returns `NeedsPassword(reason)`: `NoSibling`, `SignedOut`, `Offline` or `Refused`.
- Android, `AccountsCenter`: `accounts` (`StateFlow`), `watch`, `refresh`, `add(active, opened)` and `switch(hex, allApps)`. `add` links with the ticket; on 403/409 the account is kept device-only with `Refusal.TenantDenies` or `SecondPersonal`. Also `signOut(hex)` (this app), `removeFromDevice(hex)`, `unlink(hex, code)`, `signOutDevice(hex?)` and `profile(hex)`.
- `Accounts.pending(hex)` and `settle(hex, code)` replace `claim`: the code is removed only after it was redeemed, and its expiry is left to the server. `Accounts.rekey(from, to)` is new.

### Removed

The old account calls are removed, not deprecated. Each has a replacement:

- `SwitcherState` and its `of(...)` mappers: use `AccountsCenter.accounts` (`CenterAccount` rows with the profile, `locked`, `state` and `active`). For "instant" and "sign in again", `Accounts.fresh(hex)` and `needsSignIn(hex)` remain.
- `Linked`, `Switcher` and `Center.fetch`: use `AccountProfile`, `CenterView` and `Center.view` (or `Aduki.center()`). `Center.link(..., handle, password, code)` now returns a `CenterView`. An older server's center token is read straight into `AccountProfile`s.
- `Accounts.add(linked, tokens)`: write the entry with `AccountDirectory.upsert(profile)`, then `Accounts.save(hex, tokens)`.
- `Accounts.reconcile(switcher)` and `Accounts.refresh(...)`, with `Accounts.Change`: use `AccountsCenter.refresh()` or `AccountDirectory.reconcile(view, current)`, which never drop an account.
- `Accounts.centerToken()` and the center-token part of `CenterStore` (`save`, `id`, `token`, `clearCenter`): only `reconcile` stored that token. `CenterStore` keeps the device credential; `clear()` also removes a token an older SDK left.
- The push handoff path, `Accounts.offer`, `claim`, `offered`, `withdraw` and `holds`: a sibling asks on demand with `SuiteBroker.open(hex, key) { code -> ... }`, served by `SuiteBrokerService`. A code an older (0.7.x) sibling still leaves is taken with `Accounts.pending(hex)`, redeemed, then `settle(hex, code)`.

`Accounts` still marks the entry when it saves this app's tokens (`own.<package>`), so 0.7.x siblings stop pushing codes to an app that has its own session.

Fast, observable contacts sync, and contact photos. It uses the mail server's `GET /user/contacts/sync` and `/user/contacts/{hex}/photo` routes; against older servers the sync reads the whole list as before and photo writes are refused.

- `HttpContactTransport` reads `GET /user/contacts/sync`: a full listing the first time, then only the cards changed and removed since the stored token. Pages are applied as they arrive, one transaction each. `410`/`422` start a full listing again; `404`/`405` fall back to the full `GET /user/contacts` walk.
- The contact engine makes one pass: it reads only `hex` and `ctag` (clear columns) to find changed rows, writes only those, removes by id, and sweeps contacts missing from a full listing only after its last page. Before, every sync read and unsealed the whole book three times and rewrote every row.
- `Contacts.progress: StateFlow<SyncProgress>` (also `Sync.contacts`): `Idle`, `Running(done, total)`, `Failed(message, at)`, `Done(at, changed)`.
- Photos: `Contacts.photo(hex, refresh)` and `photo(contact, refresh)` (sealed file cache by blob id, `If-None-Match`, `data:` URIs decoded), `setPhoto(hex, bytes)` and `removePhoto(hex)` (local at once, queued as `contact.photo` / `contact.unphoto`, bytes in a sealed spool file), `photoUploads: StateFlow<PhotoProgress>`. `Aduki.Builder.photos(dir)` sets where photos are kept (default `photos` in the `store(dir)` directory).
- `pro.aduki.android.photo.Thumbnails.scale(bytes, size = 512)` scales a photo to a JPEG before upload.
- `Contact.photoHex` and `photoUpdated` (clear columns; the ObjectBox model gains two properties), `ContactRow.photoHex` and `photoUpdated`. New models in `pro.aduki.core.models`: `ContactChanges`, `ContactPhoto`, `SyncProgress`, `PhotoProgress`.
- `pro.aduki.net.http.Contacts.changes(since, limit)`, `photo(hex, etag)`, `setPhoto(hex, bytes, type, key)`, `removePhoto(hex, key)`. `ContactTransport.pages` and new `ContactStorage` reads (`unchanged`, `hexes`, `find`) have defaults, so existing implementations keep working.
- `pro.aduki.store.box.Files`: sealed files by name. `pro.aduki.sync.outbox.Photos`: the photo cache and spool.
- See `docs/services/contacts.md`.

Birthdays & dates (needs the mail server with adukilabs/aduki#254):

- `CalendarRecord.kind` (`default`, `shared`, `dates`) and `CalendarRecord.dates`; `writable` is false for the read-only `dates` calendar (it was always true).
- `Scheduling.contactDates()` and `updateContactDates(enabled)` / `updateContactDates(enabled, alarm)` (`GET|PATCH /user/calendars/dates`), with the new model `ContactDates(enabled, alarm, calendar)` and `ContactDates.Alarm` (`OnTheDay`, `DayBefore`, `WeekBefore`; null is no reminder). The overload without `alarm` leaves it unchanged. Turning the feature on or off reads the calendar list again.
- A 403 with `details.kind = "calendar.readonly"` is thrown as `AdukiException.Api` with the new `readonly` true, not as `Auth`. `Scheduling` refuses event writes into a read-only calendar and removing it before sending; the outbox drops such a queued write as `Rejected`.

## 0.7.1 (2026-11-04)

Public holidays. It needs the mail server with `GET /user/holidays`, `PUT|DELETE /user/holidays/{region}` and `GET /user/exceptions`.

- `Scheduling.holidayRegions(): List<HolidayRegion>?` lists the regions the account follows (ISO 3166-1 alpha-2 codes), empty for a new account.
- `Scheduling.holidays(start, end): List<Holiday>?` reads the holidays the server blocks on the account's schedules: the exceptions of source `holiday`, read in parts of at most 366 days. The server keeps them from yesterday to about 400 days ahead. Both reads never throw: null on failure, reported through `Faults.report`.
- `Scheduling.followHoliday(region): HolidayRegion` and `unfollowHoliday(region)` throw `AdukiException` (`Network` with `code` 400 for a region without data, 404 for unfollowing a region not followed).
- New models in `pro.aduki.core.models`: `HolidayRegion` and `Holiday` (with `date(zone)`, the date of the middle of the day in a zone, and `date` in UTC).
- `pro.aduki.net.http.Scheduling.holidayRegions`, `followHoliday`, `unfollowHoliday` and `holidays(from, to)`.
- See `docs/services/scheduling.md`.

## 0.7.0 (2026-11-03)

Profiles: name, photo, tenant, role, plan and storage. It needs the mail server with `GET /user/profile` and the `/user/avatar` routes, and Aduki ID with `PATCH /v1/accounts/{hex}`; against older servers `profile()` returns null and the writes fail with `AdukiException.Api`.

- `Aduki.profile(): Profile?` reads `GET /user/profile`. With an Aduki ID session its `storage` is the pooled total from Aduki Store (`GET /v1/quota`, audience `store`), else the mail counter (`Storage.pooled` tells which). Never throws.
- `Aduki.rename(name): Boolean` renames the account at Aduki ID (`PATCH /v1/accounts/{hex}`, 1 to 80 characters); false without an Aduki ID session.
- `Aduki.avatar(hex, etag): AvatarImage?` reads the own photo or another account's, conditionally with an `ETag`; `uploadAvatar(bytes, type): Profile?` and `removeAvatar(): Boolean` change the own photo; `avatars(emails): Map<String, Avatar>` looks photos up by address in batches of 50.
- Writes throw only `AdukiException`. `AdukiException.Api.locked` is true for a `403 locked`: the tenant locks profiles and the caller is not an owner. The cached identity is cleared after each write.
- New models in `pro.aduki.core.models`: `Profile` (with `Profile.Kind`), `Avatar`, `AvatarImage`, `TenantInfo`, `Role`, `Plan`, `Storage`. `Identity.name` (from `GET /user`), defaulted.
- `pro.aduki.net.http.Profiles` (`get`, `avatar`, `upload`, `remove`, `lookup`, `quota`) and `Id.rename(hex, name)`.
- `Endpoints.STORE` and `Endpoints.STORE_AUDIENCE`, `Options.drive` and `Builder.drive(base)` for the Aduki Store base. `Session.forget()` drops the cached identity only.
- See `docs/services/profile.md`.

Faster sealed reads:

- `Provider` keeps the loaded Keystore and each key it read or created, so opening a sealed field is one cipher operation with no Keystore load or lookup. Before, every sealed column of every row loaded the Android Keystore twice (`has` then `get`): on a device, reading 600 sealed contacts (`box.all`) took 38 to 45 s, about 2.8 ms per `openText`. A JVM test with a counting Keystore checks that opening 600 values makes no load or lookup; the on-device time after the change has not been measured yet. New `Provider.find(alias)` (one lookup, never creates) and `Provider.forget(alias)`. A kept key that stops working is read again once. The sealed format is unchanged.
- The contact repository subscribes to the stored contacts once: `search(query)`, `group(name)`, `collected()` and `manual()` derive from `observe()` instead of each running its own query (each change unsealed the whole book once per flow), and they are no longer cached per query for the life of the client. Their `value` is current without a collector.

## 0.6.3 (2026-11-02)

Safer imports and network calls, and the mail thread reader.

- `Mail.conversation(threadId): Flow<Conversation>` and `Mail.conversation(threadId, refresh)`: a thread as a reply tree with each message's own words, for the thread reader. Copies with the same Message-ID are merged. Parents come from In-Reply-To, then References, then matching subjects and recipients. Missing parents become placeholders, cycles are broken and depth is capped at 32. Siblings are ordered by `sentAt`, or `receivedAt` when that is missing or more than 48 h ahead. New models in `pro.aduki.core.models`: `Conversation`, `ThreadMessage`, `Reply`, `Forwarded`, `Confidence`, `ReplySource`, `Security`, `Transport`, `AuthResult`, `SecuritySummary` and `Person` (an alias of `Address`). See `docs/services/conversation.md`.
- With a server whose `urn:aduki:params:jmap:reading` capability lists `aduki:reply` and `aduki:security` (aduki#249), they are read and used. Otherwise, or when their confidence is low, the body is cut on the device. The client tries the server's quote start, then HTML quote markers, then attribution lines in nine languages, Outlook headers, forward dividers and `>` blocks, then an ancestor's text found at the end. It also splits off signatures (`-- `, mobile footers, and blocks repeated by the same sender). Each message has a time budget; a failure leaves it whole with `Confidence.Low`. Offline, the thread is built from the store and the body cache.
- `Jmap.Session.properties`: the `properties` list each session capability advertises. `Jmap.REPLY` and `Jmap.SECURITY` name the new `Email` properties.
- `ContentTransport.fetchAll(hexes, mode)` (default: one `fetch` per message; JMAP reads them in one `Email/get`) and `Content.bodies(hexes)`: cache-first bodies of many messages, fetched in batches, best effort offline.

- `Contacts.import` and `Contacts.ingest`: a card that fails for any reason (not only an `IllegalArgumentException`) is reported in `Imported.errors` and the other cards go on, so a malformed or unstorable card can no longer abort an import with an unexpected exception. Cancellation still stops the import.
- The import KDoc now says vCard 2.1 is read (it already was).
- Public `suspend` calls of `Aduki`, `Mail`, `Contacts`, `Groups`, `Sync`, `Scheduling` and `Settings` (and the `Contacts.ingest` flow) run on `Dispatchers.IO` and fail only with `AdukiException`, `IOException`, `IllegalArgumentException`/`IllegalStateException` (a wrong call) or `CancellationException`. Any other exception (a `JSONException` from an HTML or cut answer, `NoSuchElementException`, `NumberFormatException`, an ObjectBox exception, ...) is wrapped in `AdukiException.Protocol` (`Storage` for the database) with the original as the cause. `login`, `register`, `totp`, `logout`, `refresh`, `rights`, `me` and the `Sync` calls no longer touch the network on the caller's thread (from the main thread they failed with `NetworkOnMainThreadException`). New `Faults` in `core` holds the rule. See `docs/resilience.md`.
- `Aduki.fault: StateFlow<Throwable?>`: background work (the outbox flush on `resume()`, `watchRights`, the store observers) no longer throws to the thread's uncaught exception handler; its failure is kept here and logged by exception type only (`java.util.logging`, `pro.aduki`).
- `AdukiException.Network.retryAfter`: the `Retry-After` seconds of a 429/503 from the mail, contacts, scheduling, JMAP and OIDC clients. The outbox waits at least that long before the next attempt (capped at an hour), and `Oidc.awaitDevice` waits it out. The new parameter has a default, so existing code still compiles; code compiled against 0.6.2 that constructs `Network` must be recompiled.
- JMAP, scheduling and OIDC answers that are not JSON or lack a required field are `AdukiException.Protocol`, not `JSONException`; a JMAP session URL that does not parse is `Protocol` too. `Push.parse` skips a mailbox id that is not a string.
- A DPoP key that cannot sign fails the call with an `IOException` (cause attached) instead of a runtime exception from the interceptor; a renewed request it cannot re-sign is not retried.
- The outbox worker no longer counts a cancelled dispatch as a failed attempt, and an exception from the `rejected` callback no longer stops the drain. A failing `TokenStore.load()` during renewal is treated as no stored tokens. A fetched message body the store cannot cache is still returned. `Aduki.me()` and `Mail.openDraft` no longer swallow cancellation.
- Aduki ID calls (sign-in, sign-up, renewal, sessions) have a call timeout of four times the request timeout.

## 0.6.2 (2026-11-02)

Server reading of message bodies. It needs the mail server release with the `urn:aduki:params:jmap:reading` capability (aduki#248); against older servers bodies are read as before.

- `BodyMode { Rich, Plain, Both }` and `Mail.body(hex, mode, refresh = false)`. `Mail.body(hex)` and `Mail.body(hex, refresh)` are unchanged and read `Both`.
- With the capability, `Email/get` sends `aduki:bodyMode`. `Body.html` is the sanitised HTML and `Body.text` the text with `format=flowed` unwrapped. A `Rich` body has an empty `text`, and a `Plain` body an empty `html`.
- `Body` gains `quoteStart` (where the quoted reply starts in `text`), `hasText`, `hasHtml` and `sanitised`, all with defaults. Code that builds a `Body` with named arguments still compiles, but code compiled against 0.6.1 must be recompiled.
- `ContentTransport` gains `fetch(hex, mode)` and `sanitised()`, with defaults. `Content.body(hex, mode, refresh)` reads from the cache.
- The `Content` entity gains `quoteStart`, `hasText`, `hasHtml`, `sanitised` and `local`; ObjectBox adds them without a migration. The cache holds the whole (`Both`) body and serves every mode from it. A body cached raw is fetched again once the server has the capability; offline, the raw body is still served. Drafts saved on the device are not fetched again.

## 0.6.1 (2026-11-02)

- `Accounts` in `Mode.Shared` no longer deletes a `Mode.Own` app's private session for the same account: it drops only the copies 0.5.x kept next to the entry. `Mode.Own` copies are marked (#122).
- `Accounts.offered(hex, target)`: the handoff left for a peer and not claimed yet, without claiming it, so an app offers a new one only when needed (#123).
- `Accounts.discard(hex)`: this app's private tokens of `hex`, returned and deleted even after the account entry is gone, so the session can still be revoked (#124).

## 0.6.0 (2026-11-02)

0.6.0 is a breaking release. It needs Aduki ID with handoffs, the refresh replay window and session-on-verification, and the mail server release with the scheduling changes below; against older servers those calls fail as noted.

### Breaking

- `Accounts(context, vault)` is `Accounts(context, mode)`: `Mode.Own(vault)` (default) keeps the tokens only in the app's private store, sealed; `Mode.Shared` keeps them only in the `AccountManager` entry. No mode writes two copies (#118). `Accounts.grant`, `redeem` and `revoke` are gone (#112); `share` only grants visibility.
- `Aduki.register(hex, password, code, …)`: verification opens the session; `handle`, `name` and `backup` are gone. `Signup.verify` returns `Verified(codes, tokens)`.
- Built with Kotlin 2.4.21, OkHttp 5.5.1, ObjectBox 5.4.2, AGP 9.5.0 and Gradle 9.8.2; `pro.aduki:android` is compiled against API 37 (#114). Apps need Kotlin 2.3+, ObjectBox 5.x, `compileSdk` 37 and AGP 9. Bytecode stays Java 17.

### Accounts and sessions

- `Accounts.read(hex)` returns `Stored.Found`, `Missing` or `Sealed`, so a sealing mismatch is no longer reported as "signed out"; the silent fallback to the shared entry is gone. `migrate(hex)` is the opt-in move of this app's own 0.5.x sealed entry; `forget(hex)` ends this app's session and keeps the entry (#113).
- Handoffs (#112): `Aduki.handoff(target, ttl)` asks Aduki ID (`POST /v1/handoffs`) for a single-use code; `Accounts.offer` leaves it for that package; the peer takes it with `Accounts.claim` and opens its own session, bound to its own DPoP key, with `Aduki.redeem` (`POST /v1/handoffs/redeem`). Only the code crosses apps. `Accounts.holds(hex, package)` and `withdraw`.
- `Aduki.login`, `register`, `redeem` and `Id.signIn` take `device`, the session's name in `Aduki.sessions()` (#116).
- `Aduki.sessions`, `revoke` and `handoff` hold the renewal lock while they rotate the refresh token for the `id` audience and save the rotated token even when the call fails (a concurrent renewal could present the spent token and end the session).
- Every API client renews once on a 401 and retries; tested for mail, contacts, scheduling and JMAP, and for concurrent 401s sharing one renewal (#110).
- A process killed between a renewal and saving its result no longer loses the session: Aduki ID returns the same new refresh token when the spent one is presented again within 10 minutes, while its successor is unused (#117). The `TokenStore` contract is documented.

### Mail

- `Mail.moveMailbox` and `Mail.saveDraft` reach the server: `mailbox_move` and `draft_save` were missing from the dispatcher (#115). Drafts are created in the Drafts mailbox (looked up by role) with cc, bcc, from and HTML, a refused draft is reported instead of dropped, and the saved message takes the server's id (`openDraft` and `replace` accept the old placeholder).

### Scheduling

- `EventChanges.calendar` moves an event into another calendar (#105).
- `Stats.revenue`: `Revenue(currency, paid, refunded, net, count)` per currency, minor units (#106).
- `Scheduling.cancelBooking(hex, note)`: the host's note is kept as `BookingRecord.note` and sent to the guests (#107).
- `Scheduling.rescheduleBooking(hex, start, length?)` moves a booking as the host (#108).
- `ServiceRequest.colour` / `ServiceRecord.colour` (`#rrggbb`) (#109).

### Fixes

- The SDK frees ObjectBox's per-thread readers after its IO work and `close()` stops the store observers first; ObjectBox 5 reports closing a store under live readers of other threads as a usage error.

### Corrections to earlier release notes

Checked against the code and tests while preparing 0.6.0:

- 0.5.3 said `Mail.saveDraft` and `Mail.moveMailbox` sync through the outbox (`draft_save`, `mailbox.move`). They never did: the dispatcher refused both (#115). Fixed here.
- 0.5.3 said #91 updated the dependencies to the latest. Only coroutines, org.json and the AndroidX test libraries moved; Kotlin (2.0.21), OkHttp (4.12) and ObjectBox (4.0.3) did not (#114). Fixed here.
- 0.5.3 said refreshed tokens are "synchronized across active sessions and token stores". That held inside one process only; apps sharing an account drifted apart (#118). Fixed here.
- 0.5.4 said a peer app gets its session "without a TOTP prompt" through grants. It got the signer's own tokens, unsealed in `AccountManager` and DPoP-bound to the signer (#112). Replaced by handoffs here.
- 0.5.4 said the RFC 6350 fields round-trip "losslessly across vCard 2.1, 3.0 and 4.0". The SDK reads 2.1 and writes 3.0 and 4.0; 0.6.0 adds the 3.0 round-trip and 2.1 read tests.
- 0.5.4 said the collected marker survives sync. It travels as `X-ADUKI-COLLECTED` inside the vCard; the server has no flag of its own and its auto-collected contacts live in a separate address book without that property.
- 0.4.1-beta said `Aduki.register` signs in after verification. It reused the verified authenticator code, which Aduki ID refuses; fixed here.

## 0.5.4 (2026-10-31)

Release notes:

- Peer session grants & AccountManager visibility (#96, PR #104): Added per-package isolated token storage in `Accounts`, single-use peer session grants for companion apps without a TOTP prompt, and `Visibility` test seam for cross-app visibility verification.
- 401 Token Refresh Deadlock (#97, PR #101): Fixed deadlock when access token was expired/null but refresh token was valid. In `Authenticator.authenticate()`, when `access` is blank/null but `refresh` is present, it now proceeds directly to renew tokens instead of returning null and triggering an auth failure loop.
- RFC 6350 Card fields (#98, PR #102): Added `Postal` model and extended `Card` and `ContactRow` with typed postal addresses (`ADR`), job titles (`TITLE`), birthdays (`BDAY`), URLs (`URL`), and nicknames (`NICKNAME`) with lossless vCard 2.1/3.0/4.0 codec round-trip.
- Auto-collected contact marker (#99, PR #102): Added indexed `collected: Boolean` property to `Card`, `ContactRow`, and ObjectBox `Contact` entity with reactive `collected()` and `manual()` filter flows in `ContactRepository`.
- Progressive import & cancellation (#100, PR #103): Added `Progress` model and reactive `Contacts.ingest(InputStream): Flow<Progress>` with cooperative cancellation, partial import reports, background I/O execution, and non-cancellable outbox drain guarantee.

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
