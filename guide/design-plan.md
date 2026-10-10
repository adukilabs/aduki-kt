# Phased Implementation Plan & Test Criteria

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win.

This document defines the implementation roadmap, phased milestones, and multi-tiered test criteria for the Aduki Android Kotlin SDK.

To enable fast feedback loops and CI/CD reliability, **all tests are strictly ordered by dependency level**: self-contained algorithmic, cryptographic, database, and mocked transport tests execute first, while **tests requiring a live Aduki server are placed strictly last**.

---

## 1. Phased Implementation Roadmap

```text
┌─────────────────────────────────────────────────────────────┐
│ Phase 1: Project Scaffolding & Multi-Module Build Setup    │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 2: Core Primitives & Fastest Algorithms (xxHash, etc) │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 3: Hardware-Backed Device Security (Android KeyStore) │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 4: ObjectBox Zero-Copy Storage Layer (FlatBuffers)    │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 5: Dual Network Transport Layer (OkHttp + gRPC)       │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 6: Sync & State Engine (CONDSTORE/MODSEQ + Outbox)    │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 7: Public Facade SDK (Aduki Builder & API)     │
└──────────────────────────────┬──────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 8: Tiered Test Execution (Server Tests LAST)          │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Phase Breakdown

### Phase 1: Project Scaffolding & Multi-Module Gradle Setup
- **Deliverables**:
  - Root `build.gradle.kts` with `io.objectbox` plugin (4.0.3) and Kotlin (1.9.24 / 2.0.0).
  - Multi-module project definition (`core`, `crypto`, `store`, `net`, `sync`, `state`, `sdk`).
  - Version catalog (`gradle/libs.versions.toml`).
- **Success Criteria**: Clean project sync with zero dependency conflicts.

### Phase 2: Core Primitives & Proven Fastest Algorithms
- **Deliverables**:
  - `FastHash`: xxHash64 implementation processing over 10 GB/s for cache keys.
  - `DecorrelatedJitter`: AWS-style decorrelated jitter algorithm for backoff.
  - `AdukiDispatchers`: Dedicated dispatchers (`Store` for single-thread write transactions, `Net` for network I/O, `Crypto` for hardware crypto).
  - Memory zeroing utilities: `withWipedBytes` and `withWipedChars` using `Arrays.fill(0)`.
  - `CircuitBreaker`: Three-state atomic state machine (Closed, Open, Half-Open).
- **Success Criteria**: 100% test pass on JVM unit tests with zero Android dependencies.

### Phase 3: Hardware-Backed Device Security Layer
- **Deliverables**:
  - `KeyStoreProvider`: Hardware-backed key generation via `AndroidKeyStore` with StrongBox support and TEE fallback.
  - `EnvelopeCipher`: AES-256-GCM envelope encryption for database and attachment keys.
  - In-memory secret scrubbing wrappers.
  - SPKI Certificate Pinning and TLS 1.3 Restricted ConnectionSpec.
- **Success Criteria**: Successful encryption/decryption roundtrip in Robolectric with verified auth tags.

### Phase 4: ObjectBox Zero-Copy Storage Layer
- **Deliverables**:
  - ObjectBox entities: `Message`, `Mailbox`, `Contact`, `Outbox`.
  - B-Tree indexes on lookup and sorting fields (`hex`, `mailbox`, `uid`, `date`, `flags`).
  - Encrypted `BoxStore` initialization with KeyStore-derived master key.
  - Reactive `Query.flow()` adapters for asynchronous state emissions.
  - Atomic batch transaction helpers via `BoxStore.runInTx()`.
- **Success Criteria**: Batch insert of 10,000 entities in < 200ms in Robolectric tests.

### Phase 5: Dual Network Transport Layer (Mockable)
- **Deliverables**:
  - OkHttp 4 client with HTTP/2 multiplexing and `AuthInterceptor`.
  - `grpc-okhttp` channel with TLS and `KeyCallCredentials` metadata injector.
  - `WhoamiResolver` for proactive identity caching.
  - MockWebServer and in-process gRPC test harnesses.
- **Success Criteria**: Verified header injection, connection pooling, and error code translation against mock servers.

### Phase 6: Sync & State Engine (CONDSTORE/MODSEQ + Outbox)
- **Deliverables**:
  - `OutboxManager`: Optimistic local mutations coupled with persistent outbox journal.
  - `MailboxSynchronizer`: RFC 7162 CONDSTORE / MODSEQ incremental sync.
  - Conflict resolution: Local dirty preservation and server flag merge.
  - `MailRepository` and `ContactRepository` exposing hot `StateFlow` instances.
  - **Network transport (0.2.0):** `net.http.Mail` (typed REST client), `HttpMailboxTransport` over `GET /v1/user/mail/changes` (paging with `more`, `reset` on a stale UIDVALIDITY, moved-in messages adopted), and `HttpDispatcher` for the outbox (a stable `Idempotency-Key` per send, placeholder ids replaced by server ids, permanent 4xx dropped via `Rejected`). Contract-tested against the server's own fixtures (`guide/fixtures/sdk` in the Aduki repo).
- **Success Criteria**: Simulated network drops cause no data loss; outbox automatically flushes on simulated reconnection.

### Phase 7: Public Facade SDK (`Aduki`)
- **Deliverables**:
  - `Aduki.builder(context)` API.
  - Domain sub-services: `aduki.mail`, `aduki.contacts`, `aduki.sync`, `aduki.state`, `aduki.me()`.
  - Lifecycle integration pausing sync when app backgrounds.
- **Success Criteria**: End-to-end client initialization and observation lifecycle verified.

---

## 3. Test Criteria & Tiered Verification Matrix

```text
OFFLINE / MOCKED TIERS (Run first, 100% offline, zero server required)
  ├── Tier 1: Pure Algorithmic & Unit Tests (JVM)
  ├── Tier 2: Security & KeyStore Tests (Robolectric)
  ├── Tier 3: ObjectBox Native Storage Tests (Robolectric Native)
  └── Tier 4: Mocked Transport & Sync Contract Tests (MockServer)

SERVER-DEPENDENT TIER (Run strictly last)
  └── Tier 5: Live Aduki Integration Tests (Live REST & gRPC Server)
```

---

### Tier 1: Pure Algorithmic & Unit Tests (No Android / No Server)
*Executed via: `./gradlew :core:test`*

| Test ID | Test Case | Target Component | Success Criteria |
| :--- | :--- | :--- | :--- |
| `T1-HASH-01` | xxHash64 Vector Test | `FastHash.hash64` | Generates exact hash matching reference C xxHash64 implementation. |
| `T1-HASH-02` | xxHash64 Throughput | `FastHash.hash64` | Processes > 8 GB/s on 1MB test buffers. |
| `T1-JITT-01` | Jitter Range Bounds | `DecorrelatedJitter` | All retry delays fall strictly between `baseDelayMs` and `maxDelayMs`. |
| `T1-JITT-02` | Jitter Anti-Clustering | `DecorrelatedJitter` | Variance across 10,000 iterations verifies no clustered delays. |
| `T1-CIRC-01` | Circuit Trip to Open | `CircuitBreaker` | Trips to `OPEN` immediately after 5 consecutive failures. |
| `T1-CIRC-02` | Circuit Half-Open Probe | `CircuitBreaker` | Shifts to `HALF-OPEN` after 15s; single success restores `CLOSED`. |
| `T1-MEMO-01` | Byte Array Zeroing | `withWipedBytes` | Verifies byte array memory contains only `0x00` after execution. |
| `T1-MEMO-02` | Char Array Zeroing | `withWipedChars` | Verifies char array memory contains only `\u0000` even after thrown exception. |

---

### Tier 2: Security & KeyStore Tests (Robolectric, No Server)
*Executed via: `./gradlew :crypto:test`*

| Test ID | Test Case | Target Component | Success Criteria |
| :--- | :--- | :--- | :--- |
| `T2-KEYS-01` | KeyStore Key Generation | `KeyStoreProvider` | Successfully generates 256-bit AES master key in `AndroidKeyStore`. |
| `T2-KEYS-02` | Key Alias Persistence | `KeyStoreProvider` | Subsequent calls retrieve existing key without regenerating. |
| `T2-CIPH-01` | GCM Encryption/Decryption | `EnvelopeCipher` | Decrypted bytes match original plaintext exactly. |
| `T2-CIPH-02` | Nonce / IV Randomness | `EnvelopeCipher` | Multiple encryptions of same plaintext produce unique IVs and ciphertexts. |
| `T2-CIPH-03` | Authentication Tag Check | `EnvelopeCipher` | Modifying single ciphertext byte throws `AEADBadTagException`. |

---

### Tier 3: ObjectBox Native Storage Tests (Robolectric Native, No Server)
*Executed via: `./gradlew :store:test`*

| Test ID | Test Case | Target Component | Success Criteria |
| :--- | :--- | :--- | :--- |
| `T3-BOX-01` | Message CRUD | `Box<Message>` | Insert, read by hex, update flags, and delete message. |
| `T3-BOX-02` | 10k Batch Performance | `BoxStore.runInTx` | 10,000 messages inserted and indexed in < 200 ms. |
| `T3-INDX-01` | B-Tree Mailbox Query | `MessageQueries` | Querying by mailbox hex returns exact expected set in < 1 ms. |
| `T3-FLAG-01` | Bitmask Flag Query | `MessageQueries` | Filtering by `FLAG_SEEN` bitmask returns only matching messages. |
| `T3-FLOW-01` | Reactive Flow Emission | `MessageQueries.flow` | Inserting message triggers Flow emission with updated list within 5 ms. |
| `T3-OUTB-01` | Outbox Atomic Rollback | `OutboxManager` | Failure during transaction rolls back both message mutation and outbox action. |

---

### Tier 4: Mocked Transport & Sync Contract Tests (No Server)
*Executed via: `./gradlew :sync:test :net:test`*

| Test ID | Test Case | Target Component | Success Criteria |
| :--- | :--- | :--- | :--- |
| `T4-AUTH-01` | REST Auth Header | `AuthInterceptor` | MockWebServer verifies `Authorization: Key hm_live_...` on every call. |
| `T4-GRPC-01` | gRPC Metadata Header | `KeyCallCredentials` | In-process gRPC interceptor verifies metadata header injection. |
| `T4-SYNC-01` | CONDSTORE Delta Application | `MailboxSynchronizer` | Mock delta (3 new, 1 modified, 2 removed) correctly reconciles in ObjectBox. |
| `T4-SYNC-02` | UIDVALIDITY Reset | `MailboxSynchronizer` | Changing `uidvalidity` wipes local mailbox messages and re-initializes sync. |
| `T4-OUTB-01` | Outbox Network Retry | `OutboxWorker` | Mock 500 error triggers retry with jitter; subsequent 200 completes action. |

---

### Tier 5: Live Aduki Integration Tests (SERVER REQUIRED - PUT LAST)
*Executed via: `./gradlew :sdk:connectedCheck -Daduki.live=true`*

| Test ID | Test Case | Server Dependencies | Success Criteria |
| :--- | :--- | :--- | :--- |
| `T5-LIVE-01` | Live Whoami Resolution | Aduki REST API active at `:443` or `https://mail.aduki.pro/v1`. | Resolves user hex, tenant hex, and scopes against live database. |
| `T5-LIVE-02` | Live gRPC Connection | Aduki gRPC service active at `:8443` or `grpc.aduki.pro:443`. | Completes TLS handshake and invokes `SessionService.Whoami`. |
| `T5-LIVE-03` | Live Mailbox Listing | Test tenant populated with standard mailboxes. | Populates local ObjectBox mailboxes from live server. |
| `T5-LIVE-04` | Live CONDSTORE Delta Sync | Server mailbox with newly delivered test message. | Receives new message UID via `MailboxSyncReq` and stores in ObjectBox. |
| `T5-LIVE-05` | Live Outbox Send & Delivery | Active SMTP submission service (`:587`). | Enqueues outbound message offline; reconnects; verifies delivery and server status. |

