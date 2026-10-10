# PROGRESS: what is done, what remains, what is unverified

Written 2026-10-10 from `git log` and a read of the code. **Nothing in this repository has been compiled or tested by the people or AI who wrote this**: the VPS they used had no JDK, no Gradle and no Android SDK. Every "done" below means "code and tests exist and were reviewed by reading", not "tests pass". Item 1 turns that into fact.

## Done (code present, reviewed by reading only)

- K1: packages `pro.aduki.*`, `Aduki`, `AdukiException`, Maven group `pro.aduki` (PRs #10, #11).
- K2: `net.http.Id` client (per-audience token cache, serialized rotation, 401 renews once, spent refresh dropped), wired into `Aduki` (PR #12, #13).
- K3: `net.http.Events` rights stream over `/jmap/eventsource`, `Aduki.watchRights()` (PR #13).
- K5 protocol part: `crypto.dpop.Key`, `Software` keys, `net.http.Dpop` interceptor, `Aduki.builder().dpop(key)` (PR #13).
- K7: `net.http.Oidc` relying-party client (PKCE S256, EdDSA id token validation) (PR #13).
- Docs restructure: public mdBook in `docs/`, internal `guide/`, this `AI/` folder.

## Remaining, in order

### 1. Get a JDK and run the tests (blocks everything else)

- Install JDK 17 (the CI uses Temurin 17). `cd` to the repo, `./gradlew --no-daemon test`. One build at a time.
- Verify: all modules compile and every test passes. Expect real failures: ~1,400 lines of slice 3 and 4 code (`id.kt`, `events.kt`, `dpop.kt`, `oidc.kt`, `client.kt` changes) and their tests were never compiled.
- Gradle version mismatch to resolve: the wrapper says Gradle 9.6.0 (`gradle/wrapper/gradle-wrapper.properties`), `.github/workflows/publish.yml` installs Gradle 8.7 and runs `gradle test`. Decide one, make the workflow use `./gradlew`. Verify by a green CI run.
- The `store` module applies the ObjectBox Gradle plugin; check it generates `MyObjectBox` on a plain JVM (needs the ObjectBox native lib for the host OS for tests that open a store).

### 2. Merge slice 4 into `main`

PR #13 (K2 wiring, K3, K5, K7) was merged into branch `code/slice-3`, not into `main`; `main` ends at PR #12. Open a PR `code/slice-3` to `main` (after item 1 is green). Verify: `git log origin/main` contains `88fbb4e`. The docs-restructure PR from this task was branched from `code/slice-3` for that reason and includes those commits until they reach `main`.

### 3. K0: Maven Central namespace `pro.aduki` (owner action)

- The owner adds the DNS TXT record on `aduki.pro` that Sonatype Central asks for and verifies the namespace in the Central portal. Decision D-KT-1 (group move) is made; the verification is not done as far as the repo shows.
- Then: signing key and `SIGNING_KEY`, `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` as repository secrets; tag `v0.3.0` (or newer); `publish.yml` uploads the bundle. Verify: `https://central.sonatype.com/artifact/pro.aduki/sdk` shows the version and a clean Gradle project resolves `pro.aduki:sdk:<version>`.
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

### 9. Claims in the code that are wrong or unproven (fix or verify)

- `store.box.Factory.create(dir, key)` ignores `key`: the database is not encrypted. Decide: implement ObjectBox encryption with a Keystore-held key (`Envelope`/`Provider` are unused building blocks), or keep it documented as unencrypted (the public docs now say so).
- `crypto.tls.Pinning` holds two SPKI pins (`PIN_PRIMARY`, `PIN_BACKUP`); neither was checked against the live certificates, and the backup value looks like a placeholder. A wrong pin breaks every default-client request in production while `secure(true)`. Verify with `openssl s_client -connect mail.aduki.pro:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64` and compare; same for `grpc.aduki.pro`.
- API key prefix: the SDK sends `Key <key>` only for values starting `hm_` or `key_` (`client.kt`, `auth.kt`, `login.kt`). `hm_` is the pre-rename (hermes) prefix; confirm against the Aduki Mail server which prefix it issues now and drop the one that is obsolete.
- `Options.maxRetries` is read by nothing. `Circuit` is not used by any client. `Channel` (gRPC) is not used by `Aduki`. `Lifecycle.pause()` stops nothing. Decide whether to wire or delete.
- Contacts sync has an engine but no HTTP transport (`ContactTransport` has no implementation); `client.contacts.sync` works only if the caller built the engine with their own transport. Check against the server's contacts route.
- `sdk/.../live.test.kt` falls back to a placeholder API key `hm_live_test_credential_hex`.
- `whoami` stays on mail `GET /user` because Aduki ID has no whoami route; `Identity.scopes` and `tier` are filled only if mail returns them.
- No LICENSE file exists although the README states Apache 2.0 and the old badge linked to `LICENSE`. Add it (owner to confirm the license).
- No benchmark harness exists. The numbers once in the docs had no source; if performance claims are wanted, write a JMH or Android Macrobenchmark first.

### 10. Docs hosting

- Build: `mdbook build docs` (mdBook is not installed by this repo; the CI action installs it). `docs/book.toml` has `site-url = "/kt/"`. Verify the built site works under `https://docs.aduki.pro/kt/` (links are relative).
- `.github/workflows/book.yml` still deploys to GitHub Pages; with the site now at `docs.aduki.pro/kt` decide whether to remove the deploy job (the build job stays as a check). Owner decision.
- Pages in `docs/` that were not re-verified line by line against the code: `services/mail.md`, `services/contacts.md`, `services/sync.md`, `services/outbox.md`, `services/scheduling.md`, `reactive/*`, `store/entities.md`. They were written for 0.2.0 and spot-checked; run them against item 1's green build and a live server, and fix drift.

## Needs the real server

Items 1 (CI or a box with a JDK), 3 (Central), 5, 7 (live Aduki ID), and the live tier (`AI/SKILLS/live-tier.md`) against a running Aduki ID and Mail with a test account that has an authenticator.

## Needs owner decisions

- D-KT-1 (Maven group `pro.aduki`, relocation POM): decided 2026-10-08; execution pending (item 3).
- D-KT-3 (the `rights` source for clients without a Mail audience): open.
- D-KT-5 (Android items K4, K6, Keystore K5 are verified only on a device or CI emulator): decided; infrastructure missing.
- GitHub Pages deploy of the book: remove or keep (item 10).
- Encryption of the local database: implement or leave documented as unencrypted (item 9).
