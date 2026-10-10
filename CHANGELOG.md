# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (`AI/SKILLS/release.md`); the entries below are not released yet.

## Unreleased

Added:

- Sealed columns: `Vault` (AES-256-GCM, versioned format with a key id for rotation, legacy plaintext readable) and `Sealing`; `Message.preview`, `Message.blob`, `Contact.vcard`, `Contact.company`, `Appointment.notes` and the outbox payload are sealed when a vault is installed (`Aduki.Builder.secureStore(provider)`, `Factory.build(dir, vault)`, the Keystore provider by default on Android). Indexed metadata stays in the clear; the database file as a whole is not encrypted. Android Keystore behaviour is unverified.
- `HttpContactTransport`, `net.http.Contacts`, `Builder.contactStorage`.

Breaking:

- The gRPC host is dropped from the platform (D-HOST-5). Removed `Endpoints.GRPC_HOST`, `Endpoints.GRPC_PORT`, `Options.grpcHost`, `Options.grpcPort`, `Aduki.Builder.grpc(...)`, `pro.aduki.net.grpc.Channel`, `pro.aduki.net.grpc.Credentials`, and the grpc dependencies.
- TLS pinning is opt-in. The built-in pins (`Pinning.PIN_PRIMARY`, `PIN_BACKUP`, `REST_HOST`, `GRPC_HOST`, `Pinning.pinner()`) are removed: they were never verified against a live host. Pass your own with `Aduki.builder().pins(Pin(host, hash, backup))` (`Options.pins`); with none, the default client does no pinning.
- `Factory.create(dir, key)` lost its ignored `key` parameter (now `create(dir, vault)`).
- `Options.maxRetries` was removed (it was read by nothing).
- The `Authorization` scheme no longer depends on the token prefix: a credential given to `Builder.key(...)` is sent as `Key`, everything else as `Bearer`. A key given through `token(...)` is now `Bearer`.
