---
Spec-ID: ADK-KT-002
Title: aduki-kt progress
Category: KT
Status: Draft
Version: 8
Depends-on: ADK-KT-003
---

# aduki-kt progress

Facts from the repository on 2026-10-10 (git log of `code/slice-4`). Update in the same PR as the change.

## 1. Build layout

Gradle multi-project, plain `kotlin("jvm")` modules plus the opt-in Android library `android` and the `sample` app (`-Paduki.android=true`), ObjectBox 4.0.3,
OkHttp, coroutines, org.json. Maven group `pro.aduki` (was `io.github.adukilabs`); `release = "0.4.0"` in the root `build.gradle.kts`.

## 2. Modules

| Module | Exists |
|---|---|
| `core` | models (tokens, center, identity, mail, schedule), config (endpoints, options), memory (pool, ring, wipe, hash), retry (circuit, jitter), errors (`AdukiException`) |
| `crypto` | Android Keystore provider (`aduki_master`, no StrongBox request), AES-256-GCM `Envelope`, `Guard` sanitizer, TLS pinning (`Pinning`), DPoP `Key` interface with `Software` P-256 / Ed25519 keys |
| `net` | HTTP: login (submit, refresh, logout, totp), `Id` (K2), `Center` (link, fetch, unlink, register, unlock), `Events` (K3), `Dpop` (K5), `Oidc` (K7), mail, scheduling, whoami, REST envelope reader, bearer interceptor (`Scheme` picks the `Authorization` scheme) |
| `store` | ObjectBox entities (message, mailbox, contact, appointment, slot, service, outbox, sync), batch queries, box holder |
| `sync` | mailbox, contact, schedule engines; outbox manager and worker; HTTP transport and dispatcher; merge reconcile |
| `state` | repositories (mail, contact, appointment, session) |
| `sdk` | `Aduki` (builder incl. `identity` and `dpop`, `login`, `me`, `totp`, `refresh`, `rights`, `watchRights`, `unwatchRights`, `logout`, `pause`, `resume`), mail, contacts, scheduling, sync, lifecycle |

All have unit tests (none was run on this VPS: no JDK, see section 4); there is a live test (`live.test.kt`) in `net` and `sdk`.

## 3. Against the platform plan

| Phase | Item | State |
|---|---|---|
| P2 | package names | done in code (K1): packages `pro.aduki.*`, `Aduki`, `AdukiException`, no compatibility shims; Maven group `pro.aduki` set in the build, but the namespace is not yet verified (K0) and the relocation POM for `io.github.adukilabs:sdk` is not done |
| P2 | Aduki ID sign-in (`POST /v1/sessions`, audience `mail`), 401 renewal, revoking sign-out | done |
| P2 | K2 Aduki ID client `net.http.Id`: sign-in, one access token per audience (`mail`, `id`, `space`, ...) cached with expiry, serialized refresh rotation, 401 renews once and retries once (never loops), a spent refresh is dropped and never reused, sign-out; MockWebServer tests (`id.test.kt`) | done (slice 3), and wired into `Aduki` (slice 4): `Aduki` renews, rotates and signs out through `Id`; the duplicate renewer is gone (`Session` stays the observable copy and is adopted into `Id`); a refused refresh is now dropped from the session and never presented again. `whoami` stays Mail `/user` because Aduki ID has no whoami route |
| P4 | `Center` client: link, switcher, unlink, device key register, unlock | done |
| P4 | `rights` push handling: renew the access token on `{"@type":"Rights"}` | done (`Aduki.rights`) and K3 done (slice 4): `net.http.Events` reads `GET {mail host}/jmap/eventsource` (`event: rights`, the server then ends the stream), reconnects at once after a rights event and with decorrelated jitter after a failure; `Aduki.watchRights()` / `unwatchRights()`; fake-server tests (`events.test.kt`, `signin.test.kt`). A client without a Mail audience still needs its own product's event (D-KT-3) |
| P4 | Android authenticator (`AccountManager`, multiple accounts) | built (branch `code/kt-android`): module `android`, `Accounts`, `AdukiAuthenticator`, `SwitcherState`; Robolectric tests and an emulator test that the system registers the type; the 1 s `links = deny` removal is verified as logic (fake server, 0.4 s on the emulator) but not end to end; no switcher screen except the sample app |
| P4 | access tokens carry no center data (test) | the client side is asserted (center token never in an account entry or access token, `AccountsTest`); the server side (token contents) is `id`'s |
| P9 | DPoP (proof signing, `jti`, nonce) | protocol part done (K5, slice 4): `crypto.dpop.Key` (sign-only interface, `ES256` and `EdDSA`, RFC 7638 `jkt`) with `Software` P-256 and Ed25519 keys; `net.http.Dpop` (proof with `htm`, `htu`, `iat`, random `jti`, `ath`, `nonce`; interceptor: proof on credential-less calls, `Authorization: DPoP` for tokens whose `cnf.jkt` is this key, one retry on `DPoP-Nonce`); `Aduki.builder().dpop(key)`. Keystore-backed `Key` built (`AndroidKey`, branch `code/kt-android`; instrumented tests on an API 34 emulator, software-backed: P-256, `getEncoded()` null, signatures and DPoP proofs verify, the key survives a force-stop). **Not done**: hardware-backed and StrongBox runs, and the contract test against the `id` test server (the proofs are verified by an independent verifier in the unit tests; the `id` server accepts `ES256` since S-ID-14) |
| P9 | passkeys (WebAuthn / Credential Manager) | protocol part done (branch `code/kt-next`): `net.http.Passkeys` (begin and finish for register, step-up and sign-in, list, remove, credential shaping; fake-server tests, never run against `id`). Credential Manager step built (`android` module: `CredentialManagerAuthenticator`, `PasskeyFlow`; request shaping, error mapping and flow tested with fakes). **Not verified**: a real ceremony with a credential provider |
| P9 | OIDC "Sign in with Aduki" client | done as a client library (K7, slice 4): `net.http.Oidc` (discovery check, PKCE `S256`, state, nonce, `iss` check, code exchange with `client_secret_basic`, `client_secret_post` or `none`, rotating refresh, userinfo, revocation, optional DPoP, `EdDSA` id token validation with `kid` and one JWKS refetch, `at_hash`); MockWebServer tests (`oidc.test.kt`). **Not run**: the conformance suite of the `id` repo (it is documented there, not installable, and cannot pass while PKCE is mandatory) and a live run against `id.aduki.pro`; Custom Tabs step built (`android` module: `SignIn`, `RedirectActivity`; Robolectric tests; not run in a real browser) |
| P9 | device authorization grant (RFC 8628) | done as a client (branch `code/kt-next`): `Oidc.startDevice`, `pollDevice`, `awaitDevice` (interval, `slow_down` +5 s, expiry, error mapping); fake-server tests; never run against `id` |
| P4 | `Center.revoke` (device credential revocation) and first tests of the `Center` client | done (fake server) |
| Space | Space client | JVM draft (branch `code/kt-next`): `net.http.Space` (DPoP-bound tokens through `Id`, envelope, `AdukiException.Api`, lists, `Idempotency-Key`, `ETag`; tasks, check-ins, tickets, search, settings); fake-server tests; not wired into `Aduki`, no store, DTOs still Draft at Space (ADK-KT-003 section 3) |

## 3.2 Merge state and history (from git log)

| PR | Branch | Content | State |
|---|---|---|---|
| #8, #9 | `next/space-overhaul`, `next/decisions-1` | `Next/` README, progress, plan, SKILLS.md; Maven group decision | merged to `main` 2026-10-08 |
| #10 | `code/slice-2` | K1: `pro.aduki.hermes` to `pro.aduki`, `HermesClient` to `Aduki`, Maven group `pro.aduki` | merged to `main` 2026-10-10 |
| #11 | `code/rename-hermes` | every remaining `hermes` name to `aduki`; shims and the legacy Keystore alias removed | merged to `main` 2026-10-10 |
| #12 | `code/slice-3` | K2 `Id` client | merged to `main` 2026-10-10 |
| #13 | `code/slice-4` | `Id` wired into `Aduki`, K3, K5 protocol part, K7 | merged **into `code/slice-3`, not into `main`** (its base was `code/slice-3`); `main` stopped at #12 when this was written. Open a PR `code/slice-3` to `main` (or merge it) so the slice 4 code reaches `main` |

Not started: K0 (Maven Central namespace), K4 (authenticator), K6 (passkeys), Android Keystore `Key`, relocation POM, Space client.

## 3.3 Known code facts that docs must not overstate

- The ObjectBox database file is **not encrypted as a whole**: ObjectBox 4.0.3 has no encryption option. All personal text columns are sealed by `Vault`/`Sealing` (`security.md` section 4); structural metadata is in the clear; Android Keystore behaviour is unverified (device run pending). Session tokens live in memory, not in a store.
- `Options.secure` only allows TLS pinning on the default HTTP client, and pinning is opt-in (`Options.pins`, none shipped); `Options.maxRetries` was removed.
- `Circuit` is a standalone utility; nothing in the SDK wraps calls with it.
- `Lifecycle.pause()` only flips a flag and calls listeners; the one internal listener flushes the outbox on resume.
- Contacts sync: `HttpContactTransport` reads `GET /user/contacts` in full each time (no incremental REST route; rows carry no vCard). Tested with MockWebServer only, never against a server.
- No benchmark harness exists; the benchmark numbers that used to be in the docs had no source and were removed.

## 3.5 Phase 1 fixes (branch `code/phase1-fixes`, local, 2026-10-10)

Built: gRPC removed (D-HOST-5); pinning opt-in via `Options.pins`; one `Scheme` helper for the `Authorization` scheme (server accepts `Bearer` or `Key` for both credential kinds); `HttpContactTransport` and the `net.http.Contacts` client (full-list reconcile, see `docs/services/contacts.md`); `Options.maxRetries`, the placeholder live key and the dead `key` parameter of `Factory.create` removed; sealed payload columns (`Vault`, `Sealing`, `Builder.secureStore`); `CHANGELOG.md` added.
Verified: `./gradlew --no-daemon clean test --continue` is BUILD SUCCESSFUL on JDK 17 and on JDK 22 with 184 tests, 6 skipped (live tier, no server), 0 failures. Pinning against a generated local TLS certificate only; contacts against MockWebServer only.
Not verified: any real host (`mail.aduki.pro` did not answer on 443), any device, database encryption (not built).

## 3.6 Branch `code/kt-next` (2026-10-11)

Built on the JVM, fake servers only: device authorization grant in `Oidc`; `Passkeys`; `Center.revoke` and `Center` tests; `Space` client draft; B14 (`Envelope` uses the cipher-generated IV and `Provider` keeps Keystore randomized encryption required, tested with a Keystore-like JCE provider that refuses caller IVs); B16 docs (delete the old database after in-place sealing).
Verified: `./gradlew --no-daemon test --continue` on JDK 17 and JDK 22 (numbers in the branch report). Not verified: anything on a device, any real `id` or Space server.

## 3.7 Branch `code/kt-android` (2026-10-11)

Built: `android` module (AGP 8.13.2, `minSdk` 26, `compileSdk` 34, opt-in `-Paduki.android=true`; Gradle wrapper moved to 9.5.1 because AGP 8.13 fails on 9.6.0), `AndroidKey`, `crypto.dpop.Der`, `Accounts` / `AdukiAuthenticator` / `SwitcherState`, `SignIn` / `PendingStore` / `RedirectActivity`, `CredentialManagerAuthenticator` / `PasskeyFlow`, a `sample` app and `ANDROID-TESTS.md` for the owner's phone.
Verified: `./gradlew test` on JDK 17 and JDK 22: 243 tests, 6 skipped (live tier), 0 failures (baseline 240); with `-Paduki.android=true` on both JDKs: BUILD SUCCESSFUL and 34 Robolectric tests (debug and release variants); `:android:connectedDebugAndroidTest` on an API 34 x86_64 AOSP emulator without KVM (boot about 5 minutes): 14 tests, 13 passed, 1 skipped (the second phase of the restart test, run separately after `force-stop`: passed). Keystore there is software-backed.
Not verified: hardware Keystore, StrongBox, user-authentication keys, a real Credential Manager ceremony, Custom Tabs in a real browser, the real `id` and Space servers, Android versions other than 14, the end-to-end 1 s removal.
Finding: Aduki ID device credentials accept only Ed25519; Keystore keys are P-256, so a Keystore key cannot register for center unlock until `id` accepts ES256 device credentials.

## 3.4 Slice 4 scope

Built (plain JVM, one commit each): K2 wiring into `Aduki`, K3 rights stream, K5 protocol part, K7 client.
Skipped, Android only: K4 (Account Center authenticator, `AccountManager`), K6 (passkeys, Credential Manager),
the Android Keystore `Key` and device runs of K5, the browser/Custom Tabs step of K7. K0 (Maven Central) is out of scope.
Hosts: `id.aduki.pro` (Aduki ID, `https://id.aduki.pro/v1`), `mail.aduki.pro` (REST `https://mail.aduki.pro/v1`,
JMAP EventSource `https://mail.aduki.pro/jmap/eventsource`), `store`, `files`, `space`, `chat` `.aduki.pro` (no client yet).

## 4. Environment

The VPS had no JDK on 2026-10-08 and 2026-10-10 (first record). The phase 1 build box has JDK 17 and JDK 22 (`/opt/jdk17`, `/opt/jdk22`); no Android SDK. `/dev/kvm` is present but 8 GB does not run an emulator beside Gradle. The next step on the real server is `AI/PROGRESS.md`.

## 5. Changelog

- v8 (2026-10-11): section 3.7 and the Android rows of section 3 (`code/kt-android`).
- v7 (2026-10-11): section 3.6 and the matching rows of section 3 (device grant, Passkeys, Center.revoke, Space draft, B14).

- v6 (2026-10-10): phase 1 fixes (section 3.5); facts in 3.3 corrected for encryption, pinning, contacts transport.

- v5 (2026-10-10): merge state from git log, facts section 3.3, docs restructure (`Next/` moved to `guide/`).
- v4: slice 4: `Id` wired into `Aduki`, K3 rights stream, K5 protocol part, K7 client (section 3.4).
- v3: slice 3 takes K2 only (the `Id` client and tests); K3 to K7 not started.
- v2 (adversarial review, second pass): environment facts (no JDK, Gradle or Android SDK on the VPS; `/dev/kvm` present), DPoP algorithm gap, `rights` source.
- v1: first record.
