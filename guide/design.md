# Aduki Android SDK Design Specification

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win.

This document details the systems design, performance optimizations, memory architecture, and concurrency models for the Aduki Android Kotlin SDK.

---

## 1. Core Engineering Pillars

1. **Sub-Millisecond Query Response**: Eliminate the SQLite query planner and SQL string parser. Read directly from memory-mapped files using FlatBuffers pointers.
2. **Deterministic Memory Profile**: Prevent Garbage Collection (GC) pauses by recycling byte buffers, using primitive arrays, and avoiding unnecessary object allocations.
3. **Hardware-Enforced Security**: Cryptographic keys reside inside dedicated hardware modules (Android KeyStore StrongBox or TEE); unencrypted secrets never hit disk.
4. **Resilient Reactive State**: Provide synchronous-like responsiveness to the UI via an offline-first transactional outbox backed by reactive `StateFlow` streams.
5. **Battery Conservation**: Minimize radio wakeups using batched network operations, HTTP/2 multiplexing, and Doze-aware background synchronization.

---

## 2. Zero-Copy Pipeline & ObjectBox Integration

### Why Traditional Room/SQLite Fails High-Performance Mobile Apps

In a typical Android email client with 50,000 cached messages:

- **SQLite / Room**:
  1. Executes SQL query string: parses tokens, checks AST, plans query execution.
  2. B-Tree search loads raw SQLite pages from disk into SQLite page cache.
  3. Copies rows across the JNI boundary into Android `CursorWindow` (native shared memory).
  4. Room maps cursor rows into intermediate Java/Kotlin model instances.
  5. Result: 3 memory copies, high CPU usage, and massive GC pressure leading to 16ms frame drops.
- **Aduki with ObjectBox**:
  1. Opens database files via POSIX `mmap()` — operating system kernel pages memory directly into virtual process space.
  2. ObjectBox stores entities as pre-compiled **FlatBuffers** byte buffers.
  3. Reads access attributes directly at byte offsets without unpacking or allocating intermediate objects.
  4. Result: Zero serialization overhead, zero JNI cursor copies, sub-millisecond retrieval (typically 0.2ms - 0.5ms).

```text
Room/SQLite Pipeline (3 Copies + Heavy GC):
[Disk] ──(I/O Copy)──> [SQLite Page Cache] ──(JNI Copy)──> [CursorWindow] ──(Reflection Copy)──> [Kotlin Data Objects]

Aduki ObjectBox Pipeline (Zero Copy via mmap):
[Disk] ══(Kernel mmap)══> [Virtual Memory Buffer] ──(Direct Pointer Offset)──> [UI / ViewModel]
```

---

## 3. Threading & Concurrency Model

Android UI smoothness requires the main thread to never block for more than 16ms (60 FPS) or 8ms (120 FPS).

### Custom Dispatchers

The SDK defines dedicated, tuned CoroutineDispatchers rather than relying blindly on `Dispatchers.IO`:

```kotlin
object AdukiDispatchers {
    // Dedicated single-thread dispatcher for serialized write transactions to ObjectBox
    val Store: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aduki-store-worker").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()

    // Dedicated pool for network operations (HTTP/2 streams & gRPC)
    val Net: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(16)

    // Dedicated high-priority dispatcher for hardware crypto operations
    val Crypto: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4)
}
```

### Read-Write Concurrency

- **Reads**: Fully concurrent. ObjectBox uses Multiversion Concurrency Control (MVCC). Hundreds of concurrent reads never block writes, and writes never block reads.
- **Writes**: Executed in atomic batches via `BoxStore.runInTx()`. The SDK pools pending mutations (e.g., marking 20 messages as read) into a single write transaction, avoiding individual disk syncs (`fsync`).

---

## 4. Cold Startup Budget (< 20ms)

Most SDKs initialize heavy dependencies on the main thread during `Application.onCreate()`, delaying app launch. Aduki achieves a cold start under 20 milliseconds through:

1. **Deferred gRPC Connection**: Network channels and TLS handshakes are established lazily on the first background network request, not during client creation.
2. **Asynchronous Identity Resolution**: `GET /user` runs asynchronously in the background. The SDK serves cached user/tenant metadata from ObjectBox immediately.
3. **Native FlatBuffers Schema**: ObjectBox schema metadata is embedded at compile time via the Gradle plugin; there is zero runtime reflection or annotation processing during startup.

```text
Startup Timeline (Target: < 20ms)
0ms    : Aduki.builder().build() called
2ms    : Load hardware-backed key from Android KeyStore
8ms    : Initialize ObjectBox native mmap engine
14ms   : Read cached session & mailbox list from disk
15ms   : Return active client instance to Application
Async  : Launch background identity check & open HTTP/2 multiplexed socket
```

---

## 5. Power & Thermal Optimization

Mobile radios (LTE/5G) consume the most battery when cycling between idle and active states.

### Strategies Implemented

1. **Request Coalescing**: When the user scrolls through an email thread, individual message metadata requests are coalesced into a single multi-UID query.
2. **Full Jitter Exponential Backoff**: Prevents server load spikes and device radio thrashing when recovering from offline states.
3. **Doze Mode Adaptation**:
   - High-priority actions (user sending an email) trigger immediate execution.
   - Low-priority operations (background contact sync, read-receipt updates) register with `WorkManager` using `setRequiresBatteryNotLow(true)` and `setRequiredNetworkType(NetworkType.UNMETERED)`.

---

## 6. Memory Sanitization Architecture

Security without performance compromises:

```kotlin
inline fun <R> withSecureKey(crossinline block: (ByteArray) -> R): R {
    val keyBytes = KeyStoreProvider.exportEphemeralKey()
    try {
        return block(keyBytes)
    } finally {
        // Zeroize memory immediately to prevent heap dumps from exposing credentials
        keyBytes.fill(0)
    }
}
```

- Passwords and API keys are stored as `CharArray` rather than `String` whenever manipulated locally.
- Buffers are zeroed out immediately via `Arrays.fill(..., 0)` inside `finally` blocks, neutralizing memory inspection attacks.
