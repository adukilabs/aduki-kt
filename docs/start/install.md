# Installation

This guide walks you through integrating the Aduki Android Kotlin SDK into your Android application using Gradle Kotlin DSL (`.gradle.kts`).

---

## 1. Prerequisites

- **Android Studio**: Hedgehog (2023.1.1) or newer.
- **Minimum SDK**: Android API 26 (Android 8.0 Oreo) or higher.
- **Target SDK**: Android API 34+ (Android 14).
- **Kotlin Version**: 2.0.21+.
- **ObjectBox Version**: 4.0.3.

---

## 2. Configure Repositories

Because Aduki is published to **Maven Central**, no custom repository configuration is needed if your project already includes `mavenCentral()`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        
        // Optional JitPack mirror:
        // maven { url = uri("https://jitpack.io") }

        // Optional GitHub Packages repository:
        // maven {
        //     url = uri("https://maven.pkg.github.com/adukilabs/hermers-kt")
        //     credentials {
        //         username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        //         password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        //     }
        // }
    }
}
```

---

## 3. Apply the ObjectBox Plugin

In your project-level `build.gradle.kts`:

```kotlin
plugins {
    id("io.objectbox") version "4.0.3" apply false
}
```

In your application module `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("io.objectbox") // Generates FlatBuffers entity bindings at compile time
}
```

---

## 4. Add Dependencies

Add the Aduki SDK to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Aduki Android SDK Facade (Maven Central)
    implementation("pro.aduki:sdk:0.2.0")

    // Or via JitPack mirror:
    // implementation("com.github.adukilabs.hermers-kt:sdk:v0.2.0")

    // ObjectBox Zero-Copy Persistent Engine
    implementation("io.objectbox:objectbox-kotlin:4.0.3")
    implementation("io.objectbox:objectbox-android:4.0.3")

    // Network & gRPC Transports
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.grpc:grpc-okhttp:1.64.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### Granular Module Artifacts

If your application only needs specific subsystems, you can import individual modules:

| Module | Maven Coordinate | Purpose |
| :--- | :--- | :--- |
| **SDK Facade** | `pro.aduki:sdk:0.2.0` | Unified Aduki client entrypoint |
| **State** | `pro.aduki:state:0.2.0` | Live query observers & StateFlow feeds |
| **Sync** | `pro.aduki:sync:0.2.0` | RFC 7162 CONDSTORE synchronizer & Outbox |
| **Store** | `pro.aduki:store:0.2.0` | ObjectBox FlatBuffers models |
| **Net** | `pro.aduki:net:0.2.0` | HTTP/2 REST client & Auth tokens |
| **Crypto** | `pro.aduki:crypto:0.2.0` | Android KeyStore & AES-256-GCM cipher |
| **Core** | `pro.aduki:core:0.2.0` | RingBuffer, Jitter, Memory safety |

---

## 5. Upgrading from 0.1.x

0.2.0 is a breaking release:

- **`Message` fields changed.** `snippet` is now `preview`, `date` is now `receivedAt`, and `from` is split into `fromName` and `fromEmail`. The new fields are `threadId`, `keywords`, `hasAttachment`, `sentAt` and `modseq`. The local store refills from the server on the next sync; see the migration note in `guide/database.md`.
- **Outbox payloads are JSON.** `Manager.send(msg, raw)` expects `raw` to be the JSON send request. `client.mail.send` builds it for you. Entries queued by 0.1.x are still dispatched.
- **`Worker` rejects permanent failures.** It now accepts an `onRejected` callback for actions the server refused for good.
- **New HTTP layer.** Wire `HttpMailboxTransport(client.mailApi)` and `HttpDispatcher(client.mailApi, …)` into the sync engine and the outbox worker.

## 6. ProGuard / R8 Rules

ObjectBox and OkHttp require minimal ProGuard configuration. Add the following to `app/proguard-rules.pro`:

```proguard
# ObjectBox FlatBuffers Bytecode Preservation
-keepclassmembers class * {
    @io.objectbox.annotation.Entity <fields>;
}
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**

# OkHttp 4 Rules
-dontwarn okhttp3.**
-dontwarn okio.**
```
