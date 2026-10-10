---
Spec-ID: ADK-KT-002
Title: aduki-kt progress
Category: KT
Status: Draft
Version: 2
Depends-on: ADK-KT-003
---

# aduki-kt progress

Facts from the repository on 2026-10-08. Update in the same PR as the change.

## 1. Build layout

Gradle multi-project, plain `kotlin("jvm")` modules (no Android plugin yet), ObjectBox 4.0.3,
OkHttp, coroutines, org.json. Maven group `pro.aduki` (was `io.github.adukilabs`) (README shows 0.3.0).

## 2. Modules

| Module | Exists |
|---|---|
| `core` | models (tokens, center, identity, mail, schedule), config (endpoints, options), memory (pool, ring, wipe, hash), retry (circuit, jitter), errors (`AdukiException`) |
| `crypto` | Android Keystore provider, AES-256-GCM envelope, `Guard` sanitizer, TLS pinning |
| `net` | HTTP: login (submit, refresh, logout, totp), `Center` (link, fetch, unlink, register, unlock), mail, scheduling, whoami, REST envelope reader, bearer interceptor; gRPC channel and metadata |
| `store` | ObjectBox entities (message, mailbox, contact, appointment, slot, service, outbox, sync), batch queries, box holder |
| `sync` | mailbox, contact, schedule engines; outbox manager and worker; HTTP transport and dispatcher; merge reconcile |
| `state` | repositories (mail, contact, appointment, session) |
| `sdk` | `Aduki` (builder, `me`, `totp`, `refresh`, `rights`, `logout`, `pause`, `resume`), mail, contacts, scheduling, sync, lifecycle |

All have unit tests; there is a live test (`live.test.kt`) in `net` and `sdk`.

## 3. Against the platform plan

| Phase | Item | State |
|---|---|---|
| P2 | package names | done in code (K1): packages `pro.aduki.*`, `Aduki`, `AdukiException`, no compatibility shims; Maven group `pro.aduki` set in the build, but the namespace is not yet verified (K0) and the relocation POM for `io.github.adukilabs:sdk` is not done |
| P2 | Aduki ID sign-in (`POST /v1/sessions`, audience `mail`), 401 renewal, revoking sign-out | done |
| P4 | `Center` client: link, switcher, unlink, device key register, unlock | done |
| P4 | `rights` push handling: renew the access token on `{"@type":"Rights"}` | done (`Aduki.rights`); no SSE/stream subscriber feeds it yet (the source is the Aduki Mail JMAP EventSource, plan K3) |
| P4 | Android authenticator (`AccountManager`, multiple accounts) | missing; no Android module exists |
| P4 | access tokens carry no center data (test) | not asserted in the SDK tests |
| P9 | DPoP (proof signing, `jti`, nonce) | missing; no references in code. Server side accepts `EdDSA` proofs only today, which no hardware Keystore can produce on most devices (plan K5) |
| P9 | passkeys (WebAuthn / Credential Manager) | missing |
| P9 | OIDC "Sign in with Aduki" client | missing |
| Space | any Space client | none; deferred (see ADK-KT-003 section 4) |

## 4. Changelog

- v2 (adversarial review, second pass): environment facts (no JDK, Gradle or Android SDK on the VPS; `/dev/kvm` present), DPoP algorithm gap, `rights` source.
- v1: first record.
