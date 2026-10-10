# Aduki Kotlin SDK

The Aduki Kotlin SDK is a client library for the [Aduki](https://aduki.pro)
platform. It signs people in at Aduki ID, reads and sends mail, syncs contacts
and appointments, and keeps working offline.

The SDK is a set of Gradle modules built as plain Kotlin/JVM libraries. The
group is `pro.aduki`; the Android-specific parts (Keystore access, Account
Manager) use the Android framework when present and fall back to software
implementations on a plain JVM.

## What it does

- **Sign-in at Aduki ID.** Address, password and second factor produce a
  10-minute access token per audience and a rotating refresh token. The SDK
  renews on `401` and signs out by revoking the session. See [Sign-in](auth/login.md).
- **One session, many audiences.** The `Id` client caches one access token per
  audience (`mail`, `id`, ...). See [Aduki ID client](auth/id.md).
- **Rights stream.** Subscribes to the mail event stream and renews the token
  when the account's rights change. See [Token lifecycle](auth/tokens.md).
- **DPoP.** Binds sign-ins to a device key (RFC 9449). See [DPoP](auth/dpop.md).
- **Sign in with Aduki (OIDC).** An OpenID Connect relying-party client with
  PKCE for apps that use Aduki ID as their identity provider. See [OIDC client](auth/oidc.md).
- **Offline-first mail.** Local storage in ObjectBox, an outbox that journals
  every change before it is sent, and incremental sync over `GET /v1/user/mail/changes`.
  See [Mail](services/mail.md), [Sync](services/sync.md) and [Outbox](services/outbox.md).
- **Reactive state.** Repositories expose Kotlin `StateFlow` streams for UI binding.
  See [Reactive](reactive/index.md).
- **Typed REST clients** for mail, scheduling and the Account Center.

## Modules

| Module | Holds |
| :--- | :--- |
| `core` | models, errors, configuration, retry primitives (`Jitter`, `Circuit`), memory helpers |
| `crypto` | Keystore provider, AES-256-GCM `Envelope`, `Guard`, TLS pinning, DPoP keys |
| `net` | HTTP clients (`Id`, `Login`, `Center`, `Mail`, `Scheduling`, `Events`, `Dpop`, `Oidc`) |
| `store` | ObjectBox entities and batch transactions |
| `sync` | mailbox, contact and schedule sync engines; the outbox |
| `state` | repositories that expose `StateFlow` |
| `sdk` | the `Aduki` facade |

## Status

Not yet available: Account Center authenticator for Android, passkeys, and a
Keystore-backed DPoP key. The local database file is not encrypted; only sensitive payload columns are sealed (unverified on Android). See
the individual pages for the exact behaviour of each feature.

## Where to start

1. [Installation](start/install.md)
2. [Configuration](start/config.md)
3. [Sign-in](auth/login.md)
4. [Mail](services/mail.md)
5. [Aduki reference](reference/client.md)
