# PROGRESS: what is done, what remains, what is unverified

Written 2026-10-10 from `git log` and a read of the code. Update 2026-10-10 (branch `code/phase1-fixes`): the build now runs on a box with JDKs. `./gradlew --no-daemon test --continue` passes on JDK 17 and JDK 22 (counts in `guide/progress.md` section 3.5). Nothing Android has ever run; items marked "pending device" stay unverified. Older "done" lines below were written by reading and are now backed by the JVM test run, except where stated.

## Done (code present, reviewed by reading only)

- K1: packages `pro.aduki.*`, `Aduki`, `AdukiException`, Maven group `pro.aduki` (PRs #10, #11).
- K2: `net.http.Id` client (per-audience token cache, serialized rotation, 401 renews once, spent refresh dropped), wired into `Aduki` (PR #12, #13).
- K3: `net.http.Events` rights stream over `/jmap/eventsource`, `Aduki.watchRights()` (PR #13).
- K5 protocol part: `crypto.dpop.Key`, `Software` keys, `net.http.Dpop` interceptor, `Aduki.builder().dpop(key)` (PR #13).
- K7: `net.http.Oidc` relying-party client (PKCE S256, EdDSA id token validation) (PR #13).
- Docs restructure: public mdBook in `docs/`, internal `guide/`, this `AI/` folder.

## Remaining, in order

### 1. Get a JDK and run the tests (done on JDK 17 and 22; CI matrix covers both)

- Install JDK 17 (the CI uses Temurin 17). `cd` to the repo, `./gradlew --no-daemon test`. One build at a time.
- Verify: all modules compile and every test passes. Expect real failures: ~1,400 lines of slice 3 and 4 code (`id.kt`, `events.kt`, `dpop.kt`, `oidc.kt`, `client.kt` changes) and their tests were never compiled.
- Gradle version mismatch to resolve: the wrapper says Gradle 9.6.0 (`gradle/wrapper/gradle-wrapper.properties`), `.github/workflows/publish.yml` installs Gradle 8.7 and runs `gradle test`. Decide one, make the workflow use `./gradlew`. Verify by a green CI run.
- The `store` module applies the ObjectBox Gradle plugin; check it generates `MyObjectBox` on a plain JVM (needs the ObjectBox native lib for the host OS for tests that open a store).

### 2. (closed) Slice 4 is in `main`

`origin/main` contains `88fbb4e` through the docs-restructure PR; nothing to do.

### 3. (completed) K0: Maven Central namespace `pro.aduki` & v0.4.0 release

- The owner added the DNS TXT record on `aduki.pro` and verified namespace `pro.aduki` in Sonatype Central.
- Repository secrets configured: `SIGNING_KEY`, `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`.
- Gradle wrapper pinned to 8.13 (PR #22) restoring `ProjectDependency.getDependencyProject()` API compatibility.
- Tag `v0.4.0` published to Maven Central (deployment `6e35eee5-b768-45dc-85f5-e9af11b5587f`, state `PUBLISHED`).
- Verified: `https://central.sonatype.com/artifact/pro.aduki/sdk` (and all other 6 modules) are active with HTTP 200.


### 4. K4: Account Center authenticator (Android)

`pro.aduki.account`: Android module with `AccountManager`, multiple accounts, switcher, device-key unlock through `Center`. Not started; no Android module exists in the build (all modules are `kotlin("jvm")`). Needs the Android Gradle plugin, an SDK and an emulator or device. Exit criteria (`guide/plan.md`): the solo-developer scenario of ADK-AUTH-002 section 11 passes on a device; a tenant `links = deny` removes the account within 1 s; a test that access tokens hold no center data (not asserted today). Nothing Android has ever been verified.

### 5. Keystore-backed DPoP key (K5 remainder, Android)

Implement `crypto.dpop.Key` on the Android Keystore with a non-exportable P-256 key (`ES256`). Verify on a device: tokens bound to the key; the `id` test server accepts the proof; replayed `jti` rejected by the server contract test. Also run the contract test of the DPoP interceptor against a real `id` server (today only unit tests with an independent verifier). Needs a server (`id` repo): confirm it accepts `ES256` (the plan notes S-ID-14 added it; check).

### 5b. Device credentials with ES256 (done on the JVM, Android part unverified)

`Center.register/unlock` take a `Key`, `crypto.dpop.Device` derives alg and the 65-byte point, fake-server tests with an independent verifier pass on JDK 17 and 22 (259 tests). Needs, on a machine with the Android SDK: compile the glue (`Accounts.registerDevice/unlockWithDevice`, `AndroidKey.DEVICE`, sample buttons 9 and 10), then ANDROID-TESTS.md steps 11 to 13 against an Aduki ID with PR 35. Needs credentials/owner: nothing new.

### 6. K6: passkeys (Credential Manager)

Protocol part built on the JVM (`net.http.Passkeys`, fake server only). The Credential Manager step is not started (Android only). Verify: register and authenticate against the Aduki ID test server on a device.

### 7. K7 remainder: OIDC verification

The device authorization grant client (RFC 8628) is built (`Oidc.startDevice/pollDevice/awaitDevice`, fake server only; run it against `id` once a device-enabled client exists). No certification is wanted (owner decision 2026-10-10), so the conformance suite is not a goal.

`Oidc` is a library only. Not run: the conformance suite of the `id` repo (documented there; the notes said it cannot pass while PKCE is mandatory), a live run against `https://id.aduki.pro`, and the browser or Custom Tabs step (Android). Verify with a live sign-in using a registered client.

### 8. Space client

JVM draft built (`net.http.Space`, `docs/services/space.md`, fake-server tests). Open: wiring into `Aduki`, typed models, a local store, the realtime channel, and a run against a real Space server. The Space DTOs are still Draft; needs final Space APIs (workspaces, projects, tasks, time, invoices, notifications, search), tokens from `Id` (audience `space`), DPoP (Space accepts DPoP-bound tokens only) and a rights source for non-mail clients (decision D-KT-3: ADK-AUTH-003 section 7.4).

### 9. Claims in the code: state after the phase 1 fixes

Fixed on `code/phase1-fixes` (see `CHANGELOG.md`):

- gRPC host, `Options.grpcHost/grpcPort`, `net.grpc.*` and its dependencies removed (D-HOST-5).
- `Options.maxRetries` removed; the placeholder live key removed (live test skips without `ADUKI_KEY`).
- TLS pinning is opt-in (`Options.pins`, `Builder.pins`), no built-in pins. Tested against a local TLS server only.
- The `Authorization` scheme is chosen by one helper (`net.http.Scheme`): `Key` only for a credential given to `Builder.key(...)`, `Bearer` otherwise, no prefix sniffing. The server (`aduki` `crates/api/src/helpers/auth.rs`) accepts both schemes for both credential kinds and tells them apart by token shape.
- Contacts: `HttpContactTransport` (full-list reconcile over `GET /user/contacts`; the server's JMAP `Contact/changes` is not used), `net.http.Contacts`, `Builder.contactStorage`. MockWebServer tests only; never run against a server.
- ObjectBox 4.0.3 has no encryption option (jar, native library and a plaintext-on-disk test). Owner decision: platform encryption plus sealed sensitive fields. Built: `crypto.cipher.Vault` and `store.box.Sealing` seal all personal text columns (message subject/sender/recipients/preview/blob, contact name/email/phone/company/vcard, appointment location/notes, mailbox and service names, outbox payload); ids, flags, timestamps, counters and roles stay clear; contacts have keyed blind indexes; free-text search is in memory (`guide/security.md` section 4, `docs/security/vault.md`). The database file is NOT encrypted as a whole; nothing may claim more. Schema changed for 0.4.0: old databases must be deleted.

Still open:

- Sealed columns on a device: Android Keystore key creation (randomized encryption now stays required; `Envelope` uses the Keystore-generated IV stored with the ciphertext, JVM-tested with a Keystore-like provider, device run pending), non-exportability, reinstall/backup, real `objectbox-android`. UNVERIFIED until a device run. Also: plaintext pages left in LMDB after migrating a legacy database: no compaction; documented remedy is deleting the old database (`docs/security/vault.md`).
- `Circuit` is used by no client; `Lifecycle.pause()` stops nothing: wire or delete.
- `whoami` stays on mail `GET /user` because Aduki ID has no whoami route; `Identity.scopes` and `tier` are filled only if mail returns them.
- No benchmark harness exists. Do not add numbers to docs without one.
- Contacts: rows carry no vCard and no modification time; consider JMAP `Contact/changes` for incremental sync.

### 10. Docs hosting

- Build: `mdbook build docs` (mdBook is not installed by this repo; the CI action installs it). `docs/book.toml` has `site-url = "/kt/"`. Verify the built site works under `https://docs.aduki.pro/kt/` (links are relative).
- `.github/workflows/book.yml` still deploys to GitHub Pages; with the site now at `docs.aduki.pro/kt` decide whether to remove the deploy job (the build job stays as a check). Owner decision.
- Pages in `docs/` that were not re-verified line by line against the code: `services/mail.md`, `services/contacts.md`, `services/sync.md`, `services/outbox.md`, `services/scheduling.md`, `reactive/*`, `store/entities.md`. They were written for 0.2.0 and spot-checked; run them against item 1's green build and a live server, and fix drift.

## Needs credentials/owner

- Android (branch `code/kt-android`): built and tested on the JVM (Robolectric) and on an API 34 emulator with software-backed keys: K4 authenticator, K5 `AndroidKey`, K6 Credential Manager wiring, K7 Custom Tabs, B14 and sealed columns on the real Keystore provider. Still needs a phone, see "Android tests on a phone" below: hardware Keystore and StrongBox, a real passkey ceremony, Custom Tabs against the hosted `id.aduki.pro` (needs an OIDC client registered with redirect `pro.aduki.sample:/callback` and a Digital Asset Links file for passkeys), the end-to-end `links = deny` removal time, Android 8 to 13.
- Aduki ID side for Android: accept `ES256` device credentials (`POST /v1/credentials`, center unlock are Ed25519 only), otherwise the Keystore key cannot be a center-unlock credential.
- A device-enabled OIDC client at `id.aduki.pro` (`device: true`) to run the device grant live; a test account with an authenticator and a registered passkey for `Passkeys`; a Space tenant with the `space` app and a DPoP-capable test account for `Space`.
- Maven Central namespace, signing key and secrets (item 3), relocation POM, the GitHub Pages decision (item 10): owner.
- `Circuit` and `Lifecycle.pause()` (wire or delete) and the `Aduki` entry for the Space client: design decisions, not taken here.

## Android tests on a phone (owner, after hosting)

The step-by-step list, expected results and what to paste back are in `guide/ANDROID-TESTS.md` (the lead folds it into `REQ/tests.md`). Summary: build `:sample:assembleDebug -Paduki.android=true -PadukiClient=<client> `, `adb install`, run buttons 1 to 8 (key info and survival, Custom Tabs sign-in, switcher, center refresh and `links = deny` removal, passkey register, step-up and sign-in, DPoP call to Space), then `:android:connectedDebugAndroidTest` and the two-phase restart test. Skipped honestly on the build machine: hardware Keystore, StrongBox, real Credential Manager and Play services, a real id server.

## Needs server

- TLS pins: `mail.aduki.pro` did not answer on 443 when pinning was made opt-in. Once the hosts are live, take the SPKI hash of the live chain and of the backup key (`openssl` command in `docs/security/tls.md`), then add them to the app configuration and the docs. The SDK defines none on purpose; a pin never taken from a live host must not be added.
- A live check that a real API key is accepted as `Key` and a real Aduki ID token as `Bearer` (live tier).
- Contacts sync against a real server: `GET /user/contacts` paging (`after`, `next`, limit 200), `contacts:read` scope with an Aduki ID token (the server admits `/user/*` for ID tokens).
- Items 1 (CI), 3 (Central), 5, 7 (live Aduki ID), and the live tier (`AI/SKILLS/live-tier.md`) against a running Aduki ID and Mail with a test account that has an authenticator.

## Needs owner decisions

- D-KT-1 (Maven group `pro.aduki`, relocation POM): decided 2026-10-08; execution pending (item 3).
- D-KT-3 (the `rights` source for clients without a Mail audience): the owner chose SSE in the foreground; the `Events` stream is that (K3).
- D-KT-5 (Android items K4, K6, Keystore K5 are verified only on a device or CI emulator): decided; infrastructure missing.
- GitHub Pages deploy of the book: remove or keep (item 10).
- Version: `release` is 0.4.0 (breaking changes in `CHANGELOG.md`); tag when releasing.
- Database encryption: decided (platform + sealed fields). Open: none; structural metadata in the clear is listed in `docs/security/vault.md`.
