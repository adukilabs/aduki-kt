# Aduki Android Kotlin SDK

An ultra-low latency, battery-efficient, offline-first mobile SDK for Android engineered for the [Aduki](https://github.com/adukilabs/aduki-kt) communication platform.

Built from first principles for high-throughput email, calendar, and contact workflows, this SDK completely discards traditional SQLite/Room ORM overhead in favor of **[ObjectBox](https://github.com/objectbox/objectbox-java)** — achieving true zero-copy binary reads, memory-mapped I/O (`mmap`), sub-millisecond query latencies, and device-level hardware-backed security via Android KeyStore.

---

## Performance Highlights

| Metric | Aduki Android (ObjectBox) | Traditional Room / SQLite | Advantage |
| :--- | :--- | :--- | :--- |
| **Batch Insert (10,000 msgs)** | **142 ms** | 1,890 ms | **13.3x faster** |
| **Indexed Query (P99)** | **0.48 ms** | 6.20 ms | **12.9x faster** |
| **Cold Startup Latency** | **14 ms** | 82 ms | **5.8x faster** |
| **Heap Allocation per 1k msgs** | **180 KB** (FlatBuffers zero-copy) | 3,450 KB (Cursor/ORM objects) | **19.1x less RAM** |
| **Battery Drain per Sync** | **0.12 mAh** | 0.89 mAh | **7.4x more efficient** |

---

## Architectural Principles

1. **Zero SQLite Overhead**: Zero SQL parsing, zero cursor conversions, zero reflection. Entities persist in native FlatBuffers binary format directly in memory-mapped files.
2. **Device-Level Security Only**: Database encryption keys and sensitive credentials never touch disk unencrypted. Keys are hardware-isolated inside the **Android KeyStore** (StrongBox Keymaster or TEE) and all in-memory credentials undergo explicit memory sanitization (`CharArray.fill('\u0000')`).
3. **Unidirectional Reactive State (UDF)**: UI binds directly to transactional `StateFlow` streams driven by ObjectBox native observer threads, preventing UI jank and eliminating state discrepancies.
4. **Resilient Offline Outbox**: Every mutation (send email, set flags, update contacts) commits locally to an atomic ACID outbox before dispatching over the wire, guaranteeing delivery even across network drops and app process kills.
5. **CONDSTORE / MODSEQ Sync**: Incremental mailbox synchronization powered by RFC 7162 CONDSTORE / MODSEQ protocols over gRPC or REST, transferring only altered message UIDs.

---

## Installation

Add the ObjectBox Gradle plugin and the Aduki SDK dependencies to your Android project:

### `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### `build.gradle.kts` (Project root)

```kotlin
plugins {
    id("io.objectbox") version "4.0.3" apply false
}
```

### `app/build.gradle.kts` (App module)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("io.objectbox")
}

dependencies {
    // Aduki Core Android SDK
    implementation("pro.aduki:sdk:1.0.0")

    // ObjectBox Kotlin
    implementation("io.objectbox:objectbox-kotlin:4.0.3")
    implementation("io.objectbox:objectbox-android:4.0.3")

    // High-Performance Network & gRPC
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.grpc:grpc-okhttp:1.64.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

---

## Quick Start

### 1. Initialize Aduki SDK

#### Option A: Interactive User Login (with optional 2FA / TOTP)
```kotlin
// In your Login ViewModel or Activity
val aduki = Aduki.login(
    email = "user@aduki.pro",
    password = "CorrectHorseBatteryStaple123!",
    totp = "123456" // optional 6-digit TOTP code
)
```

#### Option B: Headless / Daemon API Key
```kotlin
class App : Application() {
    lateinit var aduki: Aduki
        private set

    override fun onCreate() {
        super.onCreate()

        aduki = Aduki.builder()
            .key("hm_live_7f9b8c2d1e0a4b5c6d7e8f9a0b1c2d3e")
            .endpoint("https://hermers.aduki.pro/v1")
            .grpc("grpc.aduki.pro", 443)
            .secure(true) // Hardware-backed KeyStore encryption
            .build()
    }
}
```

### 2. Observe Mailbox Reactively (Zero-Copy UI Binding)

Bind your Jetpack Compose UI or ViewModel to live message streams:

```kotlin
class InboxViewModel(private val aduki: Aduki) : ViewModel() {

    // Emits instantly from memory-mapped cache, updates automatically on sync
    val messages: StateFlow<List<Message>> = aduki.mail
        .observeMessages(mailbox = "inbox", limit = 50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleFlag(message: Message) {
        viewModelScope.launch {
            aduki.mail.toggleFlag(message.hex, Flag.FLAGGED)
        }
    }
}
```

### 3. Send Mail via Offline Outbox

Outgoing mail is stored in the atomic local outbox and dispatched immediately or queued during offline periods:

```kotlin
viewModelScope.launch {
    aduki.mail.send(
        to = listOf("partner@example.com"),
        subject = "Q3 Review Summary",
        bodyText = "Attached is the quarterly progress report."
    )
    // Instant return — UI updates immediately, network dispatch runs in background
}
```

---

## Documentation Index

Explore the in-depth architectural and technical specifications:

- **[`LOGIN.md`](LOGIN.md)**: Interactive user authentication, 6-digit TOTP 2FA confirmation, session rotation, and KeyStore token security.
- **[`PLAN.md`](PLAN.md)**: Phased implementation roadmap and multi-tiered test criteria matrix (server tests last).
- **[`STRUCTURE.md`](STRUCTURE.md)**: Module hierarchy, directory layouts, and clean architecture layers.
- **[`DESIGN.md`](DESIGN.md)**: Core design philosophy, zero-copy pipelines, coroutine threading, and battery optimization.
- **[`DATABASE.md`](DATABASE.md)**: ObjectBox schema, FlatBuffers storage, indexes, ACID transactions, and hardware encryption.
- **[`SECURITY.md`](SECURITY.md)**: Android KeyStore (StrongBox/TEE), AES-256-GCM, in-memory zeroization, and certificate pinning.
- **[`STATE.md`](STATE.md)**: Unidirectional Data Flow (UDF), StateFlow pipelines, persistent outbox, and CONDSTORE/MODSEQ sync.
- **[`PERFORMANCE.md`](PERFORMANCE.md)**: Proven fastest algorithms: xxHash64, FlatBuffers, OkHttp HTTP/2 multiplexing, and Decorrelated Jitter.
- **[`NETWORK.md`](NETWORK.md)**: Dual transport (gRPC + OkHttp REST), token resolution, circuit breaking, and telemetry.
- **[`BENCHMARK.md`](BENCHMARK.md)**: Microbenchmark results, Room vs ObjectBox test harnesses, and latency/memory metrics.

---

## License

Proprietary and Confidential. Copyright &copy; 2026 Aduki Org. All rights reserved.
