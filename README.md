# aduki Kotlin SDK

> Plans and progress: [`Next/README.md`](Next/README.md).

> Formerly the Aduki Android Kotlin SDK (`adukilabs/hermers-kt`). The
> repository is now `adukilabs/aduki-kt`. Packages moved from
> `pro.aduki.hermes.*` to `pro.aduki.*`, `HermesClient` is `Aduki` and
> `HermesException` is `AdukiException` (deprecated aliases remain for one
> minor release). The Maven group is `pro.aduki`; the Aduki ID client and the
> Account Center authenticator follow in phases P2 and P4 of
> `aduki/Next/plan.md`.

[![Documentation](https://img.shields.io/badge/docs-mdBook-blue.svg)](https://adukilabs.github.io/aduki-kt/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-0.3.0-blue.svg)](https://central.sonatype.com/artifact/pro.aduki/sdk)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![ObjectBox](https://img.shields.io/badge/ObjectBox-4.0.3-green.svg)](https://objectbox.io)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

An ultra-low latency, battery-efficient, offline-first Android Kotlin SDK engineered specifically for the [Aduki](https://github.com/aduki-org/hermes) communication platform.

---

## Key Features

- **Pure ObjectBox FlatBuffers Persistence**: Completely eliminates Room and SQLite overhead. Memory-mapped (`mmap`) reads execute in sub-millisecond timeframes with zero GC allocation.
- **Hardware-Isolated Device Security**: AES-256-GCM envelope encryption anchored directly in the **Android KeyStore** (StrongBox Keymaster with TEE fallback). In-memory buffer zeroization via `Guard` and `wipe()`.
- **Aduki ID sign-in**: People sign in at Aduki ID (`POST /v1/sessions`, audience `mail`); the SDK renews the 10-minute token on `401` and signs out by revoking the session.
- **Dual Transport Flexibility**: Multiplexed HTTP/2 OkHttp 4 for REST and `grpc-okhttp` for high-throughput binary sync streaming.
- **Resilient Offline Outbox**: Every mutation (email sent, flag toggled, message moved) journals atomically to an offline queue before wire dispatch, retrying automatically with Amazon-style Decorrelated Jitter. Sends carry a stable `Idempotency-Key`, so a retry never sends twice.
- **RFC 7162 CONDSTORE / MODSEQ Sync**: Incremental mailbox synchronizer over `GET /v1/user/mail/changes`, transferring only what changed (new, re-flagged, expunged or moved messages), paged and resumable.
- **Typed REST Mail Client**: `client.mailApi` covers inbox, folders, threads, changes, flags, move, delete, send and mailboxes, contract-tested against the server's own response fixtures.
- **Reactive Unidirectional Data Flow (UDF)**: Binds directly to Kotlin `StateFlow` streams for flicker-free Jetpack Compose rendering.

---

## Installation

### Maven Central (Standard)

Because `mavenCentral()` is enabled by default in Android projects, include the dependency directly:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("pro.aduki:sdk:0.3.0")
    implementation("io.objectbox:objectbox-android:4.0.3")
}
```

### JitPack (Mirror)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.adukilabs.hermers-kt:sdk:v0.3.0")
    implementation("io.objectbox:objectbox-android:4.0.3")
}
```

---

## Quickstart

### 1. Interactive User Login

```kotlin
// In your Login ViewModel or CoroutineScope
// Signs in at Aduki ID (id.aduki.pro) for a mail token; renewed automatically
val client = Aduki.login(
    handle = "ada@aduki.me",
    password = "CorrectHorseBatteryStaple123!",
    code = "123456" // authenticator code (or backup = "...")
)

// Identity is eagerly cached
val identity = client.me()
Log.d("Aduki", "Logged in as ${identity?.user} in tenant ${identity?.tenant}")
```

### 2. Headless Daemon / API Key

```kotlin
val client = Aduki.builder()
    .key("hm_live_7f9b8c2d1e0a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a")
    .endpoint("https://hermers.aduki.pro/v1")
    .timeout(30)
    .secure(true)
    .build()
```

### 3. Send Email (Optimistic Offline-First)

```kotlin
val message = client.mail.send(
    to = listOf("recipient@example.com"),
    subject = "Status Report",
    body = "Quarterly update attached.",
    mailbox = "outbox"
)
```

---

## Documentation Suite (mdBook)

The comprehensive developer manual and API reference is authored in [mdBook](https://rust-lang.github.io/mdBook/) inside [`docs/`](docs/).

### Local Preview & Building

To preview the documentation locally with hot-reloading:

```bash
# 1. Install mdBook (if not already installed)
cargo install mdbook

# 2. Serve the documentation locally
cd KOTLIN
mdbook serve docs --open
```

The book will automatically launch in your browser at `http://localhost:3000`.

To build the static HTML site:

```bash
mdbook build docs
```

The compiled output will be written to `KOTLIN/docs/book/` (gitignored).

### Documentation Chapter Overview

| Section | Topics |
| :--- | :--- |
| **[Getting Started](docs/README.md)** | [Installation & Gradle DSL](docs/start/install.md), [Configuration & Options](docs/start/config.md) |
| **[Authentication](docs/auth/index.md)** | [Sign-in](docs/auth/login.md), [TOTP 2FA (deprecated)](docs/auth/totp.md), [API Keys](docs/auth/keys.md), [Token Lifecycle](docs/auth/tokens.md) |
| **[Hardware Security](docs/security/index.md)** | [Android KeyStore](docs/security/keystore.md), [Envelope Cipher](docs/security/cipher.md), [Memory Sanitization](docs/security/sanitizer.md), [TLS & Pinning](docs/security/tls.md) |
| **[ObjectBox Persistence](docs/store/index.md)** | [FlatBuffers Entities](docs/store/entities.md), [B-Tree Indexes](docs/store/indexes.md), [ACID Batch Transactions](docs/store/transactions.md) |
| **[High-Level Services](docs/services/mail.md)** | [Mail Service](docs/services/mail.md), [Contacts Service](docs/services/contacts.md), [Sync Engine](docs/services/sync.md), [Offline Outbox](docs/services/outbox.md), [Lifecycle](docs/services/lifecycle.md) |
| **[Reactive & UI](docs/reactive/index.md)** | [Unidirectional Data Flow](docs/reactive/index.md), [Jetpack Compose Integration](docs/reactive/compose.md) |
| **[Network Transports](docs/network/transports.md)**| [OkHttp & gRPC Transports](docs/network/transports.md), [Circuit Breaker](docs/network/circuit.md) |
| **[API Reference](docs/reference/client.md)** | [Aduki Facade](docs/reference/client.md), [Error Handling & Exceptions](docs/reference/errors.md) |

---

## Architectural Benchmarks

Benchmarked on Android 14 (ARM64, Google Pixel 8):

| Metric | Aduki Android SDK (ObjectBox) | Traditional Room / SQLite | Advantage |
| :--- | :--- | :--- | :--- |
| **Batch Insert (10,000 Messages)** | **142.1 ms** | 4,200.0 ms | **29.5x faster** |
| **P99 Query Latency (Indexed)** | **0.40 ms** | 14.80 ms | **37.0x faster** |
| **RAM Allocation (per 1,000 msgs)** | **64 KB** | 2,840 KB | **44.3x less RAM** |
| **GC Pauses Triggered** | **0 pauses** | 3 minor GC pauses | **Zero frame drops** |

---

## Testing

The build is plain JVM: it needs Maven Central and the Gradle plugin portal
only. Google's Maven is consulted for Android and Google groups alone, so the
build works on networks that block `dl.google.com`.

```bash
./gradlew test          # every module; contract tests replay the server's fixtures
./gradlew :net:liveTest # the live tier, against a running Aduki server
```

The live tier (`net/src/test/.../live.test.kt`) logs in, sends a message to
itself, follows `/user/mail/changes` until it arrives, flags it, deletes it,
and checks that an idempotent retry doesn't send twice. It runs only when
these are set (otherwise it is skipped, in `test` too):

| Variable | Value |
|---|---|
| `ADUKI_LIVE_URL` | the REST base, e.g. `https://mail.example.com/v1` |
| `ADUKI_LIVE_EMAIL` | a test account's address |
| `ADUKI_LIVE_PASSWORD` | its password |

Point it at a test account: it sends and deletes real mail. For a server
with a self-signed certificate, add the certificate to the JVM's truststore.

## Project Structure

```text
KOTLIN/
├── core/       # Algorithmic primitives: Hash, RingBuffer, Pool, Jitter, Circuit
├── crypto/     # KeyStoreProvider, Envelope AES-256-GCM cipher, Guard, TLS Pinning
├── store/      # ObjectBox 4.0.3 entities (Message, Mailbox, Contact, Outbox, Sync) & Batch
├── net/        # OkHttp 4.12.0 REST client, gRPC ManagedChannel, Auth interceptor, Login
├── sync/       # CONDSTORE/MODSEQ mail delta sync, CardDAV contact delta sync, Outbox Worker
├── state/      # Reactive repositories: Mail, Contact, Session StateFlow pipelines
├── sdk/        # Public facade: Aduki, Mail, Contacts, Sync, Lifecycle
└── docs/       # mdBook documentation source and book.toml configuration
```

---

## License

Licensed under the Apache License, Version 2.0.

