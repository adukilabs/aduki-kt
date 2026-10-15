# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (`AI/SKILLS/release.md`).

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
