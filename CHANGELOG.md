# Changelog

The SDK is 0.x: breaking changes can land in a minor release. The version is set by `release` in the root `build.gradle.kts` when a release is cut (`AI/SKILLS/release.md`); the entries below are not released yet.

## Unreleased

Breaking:

- The gRPC host is dropped from the platform (D-HOST-5). Removed `Endpoints.GRPC_HOST`, `Endpoints.GRPC_PORT`, `Options.grpcHost`, `Options.grpcPort`, `Aduki.Builder.grpc(...)`, `pro.aduki.net.grpc.Channel`, `pro.aduki.net.grpc.Credentials`, and the grpc dependencies.
- TLS pinning is opt-in. The built-in pins (`Pinning.PIN_PRIMARY`, `PIN_BACKUP`, `REST_HOST`, `GRPC_HOST`, `Pinning.pinner()`) are removed: they were never verified against a live host. Pass your own with `Aduki.builder().pins(Pin(host, hash, backup))` (`Options.pins`); with none, the default client does no pinning.
- `Options.maxRetries` was removed (it was read by nothing).
- The `Authorization` scheme no longer depends on the token prefix: a credential given to `Builder.key(...)` is sent as `Key`, everything else as `Bearer`. A key given through `token(...)` is now `Bearer`.
