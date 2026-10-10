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

### 3. K0: Maven Central namespace `pro.aduki` (owner action)

- The owner adds the DNS TXT record on `aduki.pro` that Sonatype Central asks for and verifies the namespace in the Central portal. Decision D-KT-1 (group move) is made; the verification is not done as far as the repo shows.
- Then: signing key and `SIGNING_KEY`, `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` as repository secrets; tag `v0.4.0` (or newer); `publish.yml` uploads the bundle. Verify: `https://central.sonatype.com/artifact/pro.aduki/sdk` shows the version and a clean Gradle project resolves `pro.aduki:sdk:<version>`.
- The README and docs say "check releases for the published version"; before verification the badge claim "Maven Central 0.3.0" (removed from the root README in this PR) was unproven.
- **Relocation POM** for `io.github.adukilabs:sdk` pointing to `pro.aduki:sdk` (plan K1, owner decision D-KT-1): not done; needs write access to the old group's namespace on Central. Verify: resolving the old coordinates redirects with a relocation warning.

### 4. K4: Account Center authenticator (Android)

`pro.aduki.account`: Android module with `AccountManager`, multiple accounts, switcher, device-key unlock through `Center`. Not started; no Android module exists in the build (all modules are `kotlin("jvm")`). Needs the Android Gradle plugin, an SDK and an emulator or device. Exit criteria (`guide/plan.md`): the solo-developer scenario of ADK-AUTH-002 section 11 passes on a device; a tenant `links = deny` removes the account within 1 s; a test that access tokens hold no center data (not asserted today). Nothing Android has ever been verified.

### 5. Keystore-backed DPoP key (K5 remainder, Android)

Implement `crypto.dpop.Key` on the Android Keystore with a non-exportable P-256 key (`ES256`). Verify on a device: tokens bound to the key; the `id` test server accepts the proof; replayed `jti` rejected by the server contract test. Also run the contract test of the DPoP interceptor against a real `id` server (today only unit tests with an independent verifier). Needs a server (`id` repo): confirm it accepts `ES256` (the plan notes S-ID-14 added it; check).

### 6. K6: passkeys (Credential Manager)

Not started. Android only. Verify: register and authenticate against the Aduki ID test server on a device.

### 7. K7 remainder: OIDC verification

`Oidc` is a library only. Not run: the conformance suite of the `id` repo (documented there; the notes said it cannot pass while PKCE is mandatory), a live run against `https://id.aduki.pro`, and the browser or Custom Tabs step (Android). Verify with a live sign-in using a registered client.

### 8. Space client

Deferred: no `space` module. Needs final Space APIs (workspaces, projects, tasks, time, invoices, notifications, search), tokens from `Id` (audience `space`), DPoP (Space accepts DPoP-bound tokens only) and a rights source for non-mail clients (decision D-KT-3: ADK-AUTH-003 section 7.4).

### 9. Claims in the code: state after the phase 1 fixes

Fixed on `code/phase1-fixes` (see `CHANGELOG.md`):

- gRPC host, `Options.grpcHost/grpcPort`, `net.grpc.*` and its dependencies removed (D-HOST-5).
- `Options.maxRetries` removed; the placeholder live key removed (live test skips without `ADUKI_KEY`).
- TLS pinning is opt-in (`Options.pins`, `Builder.pins`), no built-in pins. Tested against a local TLS server only.
- The `Authorization` scheme is chosen by one helper (`net.http.Scheme`): `Key` only for a credential given to `Builder.key(...)`, `Bearer` otherwise, no prefix sniffing. The server (`aduki` `crates/api/src/helpers/auth.rs`) accepts both schemes for both credential kinds and tells them apart by token shape.
- Contacts: `HttpContactTransport` (full-list reconcile over `GET /user/contacts`; the server's JMAP `Contact/changes` is not used), `net.http.Contacts`, `Builder.contactStorage`. MockWebServer tests only; never run against a server.
- ObjectBox 4.0.3 has no encryption option (jar, native library and a plaintext-on-disk test). Owner decision: platform encryption plus sealed sensitive fields. Built: `crypto.cipher.Vault` and `store.box.Sealing` seal all personal text columns (message subject/sender/recipients/preview/blob, contact name/email/phone/company/vcard, appointment location/notes, mailbox and service names, outbox payload); ids, flags, timestamps, counters and roles stay clear; contacts have keyed blind indexes; free-text search is in memory (`guide/security.md` section 4, `docs/security/vault.md`). The database file is NOT encrypted as a whole; nothing may claim more. Schema changed for 0.4.0: old databases must be deleted.

Still open:

- Sealed columns on a device: Android Keystore key creation (the `setRandomizedEncryptionRequired(false)` reflection call), use with Envelope's own IV, non-exportability, reinstall/backup, real `objectbox-android`. UNVERIFIED until a device run. Also: plaintext pages left in LMDB after migrating a legacy database (compaction not done).
- `Circuit` is used by no client; `Lifecycle.pause()` stops nothing: wire or delete.
- `whoami` stays on mail `GET /user` because Aduki ID has no whoami route; `Identity.scopes` and `tier` are filled only if mail returns them.
- No benchmark harness exists. Do not add numbers to docs without one.
- Contacts: rows carry no vCard and no modification time; consider JMAP `Contact/changes` for incremental sync.

### 10. Docs hosting

- Build: `mdbook build docs` (mdBook is not installed by this repo; the CI action installs it). `docs/book.toml` has `site-url = "/kt/"`. Verify the built site works under `https://docs.aduki.pro/kt/` (links are relative).
- `.github/workflows/book.yml` still deploys to GitHub Pages; with the site now at `docs.aduki.pro/kt` decide whether to remove the deploy job (the build job stays as a check). Owner decision.
- Pages in `docs/` that were not re-verified line by line against the code: `services/mail.md`, `services/contacts.md`, `services/sync.md`, `services/outbox.md`, `services/scheduling.md`, `reactive/*`, `store/entities.md`. They were written for 0.2.0 and spot-checked; run them against item 1's green build and a live server, and fix drift.

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
