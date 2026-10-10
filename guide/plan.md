---
Spec-ID: ADK-KT-003
Title: aduki-kt plan
Category: KT
Status: Draft
Version: 3
Depends-on: ADK-KT-002, ADK-AUTH-001, ADK-AUTH-002, ADK-AUTH-003
---

# aduki-kt plan

Phases refer to `plan.md` in `aduki/guide/` (P2, P4, P9). Work items are
independent pull requests, one concern each. Build rules: one shared Gradle
cache, no incremental Kotlin compile on the 8 GB VPS (`--no-daemon`, clean
modules); see `../SKILLS.md`.

## 1. Items

| # | Item | Phase | Exit criteria |
|---|---|---|---|
| K0 | Verify the `pro.aduki` namespace on Maven Central (DNS TXT on `aduki.pro` by the owner) and set up signing and publishing for the new group | P2 | namespace shows verified; a snapshot publishes under `pro.aduki` | 
| K1 | Packages are `pro.aduki.*` (`pro.aduki.core`, `.net`, ...); the client is `Aduki` and the error type `AdukiException`; no compatibility shims (no released app depends on the old names) | P2 | no old-name references in sources or tests; docs and README updated; Maven group moves to `pro.aduki` (owner decision D-KT-1, 2026-10-08): needs the Maven Central namespace `pro.aduki` verified first (DNS TXT record on `aduki.pro`, K0 below); the old `io.github.adukilabs:sdk` gets a relocation POM pointing to the new coordinates |
| K2 | Aduki ID client module: typed sessions, refresh, logout, `whoami`, audiences (`mail`, `id`, later `space`), one token cache keyed by audience | P2 | one unit test per route against MockWebServer using the REST envelope; 401 renews once, never loops; a refresh already spent is never reused |
| K3 | `rights` stream: subscribe to the push channel (`event: rights`), call `rights()`, backoff on disconnect with jitter. **Source**: the Aduki Mail JMAP EventSource (the only `rights` publisher that exists: `aduki` pushes it on every epoch change, `aduki/guide/progress.md` section 3.4; there is no `id` or gateway "rights channel"), audience `mail`. A client with no Mail audience (a future Space-only app) needs the same event from its own product (decide D-KT-3, ADK-AUTH-003 §7.4) | P4 | a rights event renews the token within 1 s in a fake-server test; reconnect resumes without a duplicate renew; a stale-token response (`auth.stale`) renews once even if no push arrived (the gate is the guarantee, the push an optimisation) |
| K4 | Account Center authenticator `pro.aduki.account` (Android module: `AccountManager`, multiple accounts, switcher, device-key unlock through `Center`) | P4 | solo-developer scenario (ADK-AUTH-002 section 11) passes on a device or emulator; tenant `links = deny` removes the account within 1 s; test that access tokens hold no center data |
| K5 | DPoP: per-device Keystore key, proof JWT per request (`htm`, `htu`, `iat`, `jti`, `ath`), server nonce retry once. **Algorithm**: Android Keystore and the Secure Enclave generate P-256 keys on every supported version (Ed25519 only on newer Android TEE keystores, not StrongBox), so the proof is `ES256` with a hardware key and `EdDSA` only with a software key; the server side must accept both (ADK-AUTH-001 §10, S-ID-14: `gate::dpop` and Space's gate step 2 accept `EdDSA` only today) | P9 | tokens issued bound to the key; replayed `jti` rejected by the server contract test; key never exported; an ES256 proof from a Keystore key is accepted by the `id` test server |
| K6 | Passkeys via Credential Manager (register, authenticate) | P9, later | register and authenticate against the Aduki ID test server on Android |
| K7 | OIDC "Sign in with Aduki" (basic and PKCE profiles) | P9, later | conformance with the id repo suite |

Order: K1, K2, K3, K4, then K5; K6 and K7 only after K5. Sessions may stay
unbound until K5 (ADK-AUTH-001 section 10, `id` README: "may stay unbound"),
so K1 to K4 work against Mail today; **K5 moves ahead of any Space client**,
because Space accepts DPoP-bound tokens only (ADK-SPACE-001, gate step 2).
K3 uses the JMAP EventSource that `aduki` already serves.

### 1.1 Where each item can be built and verified

This VPS has no JDK, no Gradle and no Android SDK (`dev.md`, checked
2026-10-08 and 2026-10-10), and 8 GB RAM does not run an emulator beside a Gradle build.

| Items | Kind | Verified where |
|---|---|---|
| K1, K2, K3, K5 (protocol parts), K7 | plain JVM modules and MockWebServer tests | the VPS after installing a JDK 17 (`--no-daemon`, one build at a time, not beside cargo), or CI |
| K4, K6, the Keystore parts of K5 | needs Android (`AccountManager`, Keystore, Credential Manager) | a device or a CI emulator; their exit criteria are **not** claimed from the VPS (decide D-KT-5) |

The `crypto` module holds the Android Keystore provider inside a plain
`kotlin("jvm")` module, so the Android-only classes compile against stubs or
are not exercised by JVM tests: K4 starts by introducing the Android Gradle
modules and moving the Keystore provider into one.

## 2. Rules

- Specs are cited by Spec-ID and section; behaviour changes bump the owning spec.
- No secrets or default credentials in tests; live tests read the endpoint from the environment and skip when unset.

## 3. Space client (deferred)

Backend only now: no Space SDK module is built. When it is, a `space` module
(audience `space`, host `space.aduki.pro`) will need these Space APIs, per
the Space specs (ADK-SPACE-*): workspaces and members, teams and rosters read
from Aduki ID (not Space), projects and tasks, time and check-ins, invoices and
their PDFs (via Store), notifications push, and search collections. It reuses K2
tokens and K5 DPoP, and the `rights` stream (K3). Until those APIs are final the
module is not started.

## 4. Status (2026-10-10)

K1, K2 (wired into `Aduki`), K3, the protocol part of K5 and K7 are in code; K0, K4, K6 and the Keystore part of K5 are open. Details and merge state: `progress.md`; what remains to verify: `../AI/PROGRESS.md`.

## 5. Changelog

- v3: paths moved from `Next/` to `guide/`; status section.
- v2 (adversarial review, second pass): K3 source corrected (JMAP EventSource, not an `id` channel); K5 algorithm (ES256 for hardware keys) and its position before any Space client; build and verification locations (section 1.1).
- v1: created.
