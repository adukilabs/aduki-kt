# Project Structure & Architecture

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win.

This document specifies the module decomposition, package layout, and design conventions for the Aduki Android Kotlin SDK.

---

## Architecture Pattern: Layered Clean Architecture

The SDK adopts a strictly decoupled, unidirectional layered architecture. Dependencies flow inward toward the domain layer. The UI layer (or client application) communicates exclusively through the public `Aduki` facade.

```text
┌─────────────────────────────────────────────────────────────┐
│                 Client Application Layer                    │
│           (Jetpack Compose, Activities, ViewModels)          │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   Aduki SDK Public API                      │
│     (Aduki, MailManager, ContactManager, State)      │
└──────────────┬──────────────────────────────┬───────────────┘
               │                              │
               ▼                              ▼
┌──────────────────────────────┐┌─────────────────────────────┐
│      Sync & State Engine     ││      Security & KeyStore    │
│  (CONDSTORE/MODSEQ, Outbox)  ││   (StrongBox/TEE, AES-GCM)  │
└──────────────┬───────────────┘└─────────────┬───────────────┘
               │                              │
       ┌───────┴──────────────┐               │
       ▼                      ▼               │
┌──────────────┐      ┌───────────────┐       │
│  ObjectBox   │      │ Dual Network  │◄──────┘
│  Zero-Copy   │      │ Transport     │
│  (FlatBuffer)│      │ (gRPC + REST) │
└──────────────┘      └───────────────┘
```

---

## Module Decomposition

To enable clean tree-shaking, minimal cold startup overhead, and isolated unit testing, the SDK is structured into focused modules:

```text
KOTLIN/
├── docs/                     # Public mdBook (docs.aduki.pro/kt)
├── core/                     # Shared models, errors, dispatchers, and memory buffers
├── crypto/                   # Hardware KeyStore integration and zero-copy envelope encryption
├── store/                    # ObjectBox entities, indexes, and reactive Flow box queries
├── net/                      # Dual-stack transport: OkHttp (REST) + grpc-kotlin (gRPC)
├── sync/                     # Incremental CONDSTORE/MODSEQ engine & persistent outbox
├── state/                    # MVI / UDF StateFlow pipelines and repository bindings
└── sdk/                      # Public facade integrating all modules with developer ergonomics
```

---

## Detailed Directory & Package Layout

### 1. `core` Module

```text
core/src/main/kotlin/pro/aduki/core/
├── config/
│   ├── options.kt            # Client configuration, base URLs, timeouts, retry counts
│   └── endpoints.kt          # Production REST and gRPC endpoint constants
├── dispatchers/
│   └── runners.kt            # Specialized CoroutineDispatchers (IO, DB, Crypto, Net)
├── errors/
│   ├── types.kt              # Sealed AdukiException hierarchy (Network, Auth, Storage)
│   └── codes.kt              # Standard error codes mapped from REST & gRPC status
└── memory/
    ├── buffer.kt             # Direct byte buffer pooling using Okio ByteString
    └── wipe.kt               # Extension functions for zeroing ByteArrays and CharArrays
```

### 2. `crypto` Module

```text
crypto/src/main/kotlin/pro/aduki/crypto/
├── keystore/
│   ├── provider.kt           # Hardware-backed Android KeyStore (StrongBox preferred, TEE fallback)
│   └── keys.kt               # AES-256-GCM master key generation and rotation policies
├── cipher/
│   ├── gcm.kt                # Streaming AES/GCM/NoPadding cipher implementation
│   └── envelope.kt           # Envelope encryption wrapper for ObjectBox database encryption
└── sanitizer/
    └── secure.kt             # AutoCloseable wrapper that wipes keys upon scope exit
```

### 3. `store` Module (ObjectBox)

```text
store/src/main/kotlin/pro/aduki/store/
├── box/
│   ├── holder.kt             # Thread-safe BoxStore singleton and lifecycle management
│   └── factory.kt            # Encrypted BoxStore factory backed by KeyStore keys
├── entities/
│   ├── message.kt            # @Entity MessageEntity (FlatBuffers storage, B-tree indexes)
│   ├── mailbox.kt            # @Entity MailboxEntity (modseq, uidvalidity tracking)
│   ├── contact.kt            # @Entity ContactEntity (vCard attributes, ctag)
│   ├── outbox.kt             # @Entity OutboxEntity (atomic mutation action queue)
│   └── sync.kt               # @Entity SyncEntity (persisted mailbox & contact sync cursors)
└── queries/
    ├── reactive.kt           # Query.toFlow() reactive bindings on ObjectBox native observer
    └── batch.kt              # Bulk insertion and update wrappers using BoxStore.runInTx()
```

### 4. `net` Module (Dual Transport)

```text
net/src/main/kotlin/pro/aduki/net/
├── http/
│   ├── client.kt             # OkHttp client builder with HTTP/2, pooling, and Brotli
│   ├── auth.kt               # Key interceptor inserting "Authorization: Key hm_live_..."
│   └── whoami.kt             # Fast session identity resolver (GET /user)
├── grpc/
│   ├── channel.kt            # OkHttpChannelBuilder setup for mobile TLS gRPC
│   ├── metadata.kt           # CallCredentials interceptor for gRPC authorization
│   └── stubs.kt              # Coroutine stubs for MailService, SyncService, SessionService
└── retry/
    ├── jitter.kt             # Decorrelated Jitter exponential backoff implementation
    └── circuit.kt            # Circuit breaker to prevent battery drain during outages
```

### 5. `sync` Module

```text
sync/src/main/kotlin/pro/aduki/sync/
├── engine/
│   ├── mail.kt               # RFC 7162 CONDSTORE/MODSEQ incremental synchronizer
│   └── contact.kt            # Contact delta synchronization via ctag / timestamp
├── outbox/
│   ├── worker.kt             # FIFO transactional queue worker executing pending mutations
│   └── dispatcher.kt         # Dispatches pending sends, flag updates, and deletions
└── reconcile/
    └── merge.kt              # Conflict resolution: Last-Write-Wins and local dirty preservation
```

### 6. `state` Module

```text
state/src/main/kotlin/pro/aduki/state/
├── repository/
│   ├── mail.kt               # MailRepository exposing StateFlow<List<Message>>
│   ├── contact.kt            # ContactRepository exposing StateFlow<List<Contact>>
│   └── session.kt            # SessionRepository managing cached identity and scopes
└── flow/
    └── operators.kt          # DistinctUntilChanged and debounce operators optimized for UI
```

### 7. `sdk` Module (Public Interface)

```text
sdk/src/main/kotlin/pro/aduki/sdk/
├── client.kt                 # Aduki entrypoint with builder pattern
├── mail.kt                   # aduki.mail surface (send, list, flag, search)
├── contacts.kt               # aduki.contacts surface (create, list, update)
└── identity.kt               # aduki.me cached identity accessor
```

---

## Naming & Style Conventions

Following the core Aduki codebase conventions:

1. **One-Word First**:
   - Entities: `Message`, `Mailbox`, `Contact`, `Outbox`, `Sync`.
   - Methods: `send()`, `list()`, `sync()`, `flag()`, `wipe()`, `resolve()`.
   - Fields: `hex`, `owner`, `created`, `seen`, `flagged`, `size`. No redundant prefixes or suffixes (`is_`, `_at`, `_id`).
2. **Concise Scopes**:
   - Instead of `MailboxSyncManager.synchronizeMailbox()`, we write `MailSync.sync()`.
   - Instead of `EncryptedObjectBoxDatabaseHelper`, we write `Store.open()`.
3. **No Hungarian Notation**:
   - Values describe what they *are*, not their data structure (`messages`, not `messageList`).
