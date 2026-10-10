---
Spec-ID: ADK-KT-002
Title: aduki-kt progress
Category: KT
Status: Draft
Version: 4
Depends-on: ADK-KT-003
---

# aduki-kt progress

Facts from the repository on 2026-10-10 (git log of `code/slice-4`). Update in the same PR as the change.

## 1. Build layout

Gradle multi-project, plain `kotlin("jvm")` modules (no Android plugin yet), ObjectBox 4.0.3,
OkHttp, coroutines, org.json. Maven group `pro.aduki` (was `io.github.adukilabs`); `release = "0.3.0"` in the root `build.gradle.kts`.

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
| P4 | Android authenticator (`AccountManager`, multiple accounts) | missing; no Android module exists |
| P4 | access tokens carry no center data (test) | not asserted in the SDK tests |
| P9 | DPoP (proof signing, `jti`, nonce) | protocol part done (K5, slice 4): `crypto.dpop.Key` (sign-only interface, `ES256` and `EdDSA`, RFC 7638 `jkt`) with `Software` P-256 and Ed25519 keys; `net.http.Dpop` (proof with `htm`, `htu`, `iat`, random `jti`, `ath`, `nonce`; interceptor: proof on credential-less calls, `Authorization: DPoP` for tokens whose `cnf.jkt` is this key, one retry on `DPoP-Nonce`); `Aduki.builder().dpop(key)`. **Not done, Android only**: the Keystore-backed `Key` (P-256, non-exportable), tests on a device, and the contract test against the `id` test server (the proofs are verified by an independent verifier in the unit tests; the `id` server accepts `ES256` since S-ID-14) |
| P9 | passkeys (WebAuthn / Credential Manager) | missing |
| P9 | OIDC "Sign in with Aduki" client | done as a client library (K7, slice 4): `net.http.Oidc` (discovery check, PKCE `S256`, state, nonce, `iss` check, code exchange with `client_secret_basic`, `client_secret_post` or `none`, rotating refresh, userinfo, revocation, optional DPoP, `EdDSA` id token validation with `kid` and one JWKS refetch, `at_hash`); MockWebServer tests (`oidc.test.kt`). **Not run**: the conformance suite of the `id` repo (it is documented there, not installable, and cannot pass while PKCE is mandatory) and a live run against `id.aduki.pro`; no browser or Custom Tabs step (Android) |
| Space | any Space client | none; deferred (see ADK-KT-003 section 3) |

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

- `Factory.create(dir, key)` ignores `key`: the ObjectBox database is **not encrypted**. `Envelope` and `Provider` are building blocks no SDK code path calls; session tokens live in memory (`Session` StateFlows), not in a store.
- `Options.secure` only allows TLS pinning on the default HTTP client, and pinning is opt-in (`Options.pins`, none shipped); `Options.maxRetries` was removed.
- `Circuit` is a standalone utility; nothing in the SDK wraps calls with it.
- `Lifecycle.pause()` only flips a flag and calls listeners; the one internal listener flushes the outbox on resume.
- Contacts sync: `HttpContactTransport` reads `GET /user/contacts` in full each time (no incremental REST route; rows carry no vCard). Tested with MockWebServer only, never against a server.
- No benchmark harness exists; the benchmark numbers that used to be in the docs had no source and were removed.

## 3.4 Slice 4 scope

Built (plain JVM, one commit each): K2 wiring into `Aduki`, K3 rights stream, K5 protocol part, K7 client.
Skipped, Android only: K4 (Account Center authenticator, `AccountManager`), K6 (passkeys, Credential Manager),
the Android Keystore `Key` and device runs of K5, the browser/Custom Tabs step of K7. K0 (Maven Central) is out of scope.
Hosts: `id.aduki.pro` (Aduki ID, `https://id.aduki.pro/v1`), `mail.aduki.pro` (REST `https://mail.aduki.pro/v1`,
JMAP EventSource `https://mail.aduki.pro/jmap/eventsource`), `store`, `files`, `space`, `chat` `.aduki.pro` (no client yet).

## 4. Environment

No JDK, Gradle or Android SDK on the VPS (checked 2026-10-08 and again 2026-10-10); nothing in this repository was compiled or tested there. `/dev/kvm` is present but 8 GB does not run an emulator beside Gradle. The next step on the real server is `AI/PROGRESS.md`.

## 5. Changelog

- v5 (2026-10-10): merge state from git log, facts section 3.3, docs restructure (`Next/` moved to `guide/`).
- v4: slice 4: `Id` wired into `Aduki`, K3 rights stream, K5 protocol part, K7 client (section 3.4).
- v3: slice 3 takes K2 only (the `Id` client and tests); K3 to K7 not started.
- v2 (adversarial review, second pass): environment facts (no JDK, Gradle or Android SDK on the VPS; `/dev/kvm` present), DPoP algorithm gap, `rights` source.
- v1: first record.
