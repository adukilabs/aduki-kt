# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (`AI/SKILLS/release.md`).

## 0.4.0 (not released yet; `release` is set to it)

Schema changed: personal text columns are now sealed and `Contact` gained two index columns, so **an existing local database from 0.3.x must be deleted** (there is no migration; no real data existed). The old plaintext read path is kept only so rows written without a vault still read.

Added:

- Sealed columns: `Vault` (AES-256-GCM, versioned format with a key id for rotation) and `Sealing`. Sealed when a vault is installed: message subject, sender name and address, recipients, preview, blob; contact name, e-mail, phone, company, vCard; appointment location and notes; mailbox and service names and descriptions; the outbox payload. `@Index` was removed from `Contact.name` and `Contact.email`; lists sort in memory. Contacts carry keyed blind indexes (`emailIndex`, `phoneIndex`, HMAC-SHA256 under a per-field key from a sealed seed) for `Contact.byEmail` and `byPhone`; free-text search stays in memory over decrypted rows (`Aduki.Builder.secureStore(provider)`, `Factory.build(dir, vault)`, the Keystore provider by default on Android). Ids, flags, keywords, timestamps, counters, roles and statuses stay in the clear; the database file as a whole is not encrypted. Android Keystore behaviour is unverified.
- `HttpContactTransport`, `net.http.Contacts`, `Builder.contactStorage`.

Breaking:

- The gRPC host is dropped from the platform (D-HOST-5). Removed `Endpoints.GRPC_HOST`, `Endpoints.GRPC_PORT`, `Options.grpcHost`, `Options.grpcPort`, `Aduki.Builder.grpc(...)`, `pro.aduki.net.grpc.Channel`, `pro.aduki.net.grpc.Credentials`, and the grpc dependencies.
- TLS pinning is opt-in. The built-in pins (`Pinning.PIN_PRIMARY`, `PIN_BACKUP`, `REST_HOST`, `GRPC_HOST`, `Pinning.pinner()`) are removed: they were never verified against a live host. Pass your own with `Aduki.builder().pins(Pin(host, hash, backup))` (`Options.pins`); with none, the default client does no pinning.
- `Factory.create(dir, key)` lost its ignored `key` parameter (now `create(dir, vault)`).
- `Options.maxRetries` was removed (it was read by nothing).
- The `Authorization` scheme no longer depends on the token prefix: a credential given to `Builder.key(...)` is sent as `Key`, everything else as `Bearer`. A key given through `token(...)` is now `Bearer`.
