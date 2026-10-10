# Installation

This guide walks you through integrating the Aduki Android Kotlin SDK into your Android application using Gradle Kotlin DSL (`.gradle.kts`).

---

## 1. Prerequisites

- **JDK**: 17 (the build and the CI use Java 17).
- **Kotlin**: 2.0.21.
- **ObjectBox**: 4.0.3, with its Gradle plugin in the app that stores data.
- **Android**: the modules are plain JVM libraries. The SDK does not set an Android `minSdk`; the Keystore code reaches Android classes by reflection and falls back to software keys elsewhere.

---

## 2. Configure Repositories

The SDK uses the Maven group `pro.aduki`. The release workflow publishes to Maven Central and to GitHub Packages (`https://maven.pkg.github.com/adukilabs/aduki-kt`). Check the repository's releases page for the current published version. `mavenCentral()` is enough for the Central artifacts:

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
        //     url = uri("https://maven.pkg.github.com/adukilabs/aduki-kt")
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
    id("io.objectbox") // Generates the entity bindings at compile time
}
```

---

## 4. Add Dependencies

Add the Aduki SDK to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Aduki SDK facade
    implementation("pro.aduki:sdk:0.3.0")

    // Or via JitPack mirror:
    // implementation("com.github.adukilabs.aduki-kt:sdk:v0.3.0")

    // ObjectBox persistence
    implementation("io.objectbox:objectbox-kotlin:4.0.3")
    implementation("io.objectbox:objectbox-android:4.0.3")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### Granular Module Artifacts

If your application only needs specific subsystems, you can import individual modules:

| Module | Maven Coordinate | Purpose |
| :--- | :--- | :--- |
| **SDK Facade** | `pro.aduki:sdk:0.3.0` | The `Aduki` entry point |
| **State** | `pro.aduki:state:0.3.0` | Repositories exposing `StateFlow` |
| **Sync** | `pro.aduki:sync:0.3.0` | RFC 7162 CONDSTORE synchronizer & Outbox |
| **Store** | `pro.aduki:store:0.3.0` | ObjectBox entities |
| **Net** | `pro.aduki:net:0.3.0` | HTTP clients (Aduki ID, mail, scheduling) |
| **Crypto** | `pro.aduki:crypto:0.3.0` | Keystore provider, AES-256-GCM cipher, TLS pinning, DPoP keys |
| **Core** | `pro.aduki:core:0.3.0` | models, errors, Jitter, Circuit, memory helpers |

---

## 5. Upgrading from 0.1.x

Version 0.3.0 also renames the packages to `pro.aduki.*`, the client to `Aduki` and the error type to `AdukiException`, and the Maven group to `pro.aduki` (it was `io.github.adukilabs`). There are no compatibility shims.

0.2.0 was a breaking release:

- **`Message` fields changed.** `snippet` is now `preview`, `date` is now `receivedAt`, and `from` is split into `fromName` and `fromEmail`. The new fields are `threadId`, `keywords`, `hasAttachment`, `sentAt` and `modseq`. The local store refills from the server on the next sync; the local store is rebuilt by a fresh sync.
- **Outbox payloads are JSON.** `Manager.send(msg, raw)` expects `raw` to be the JSON send request. `client.mail.send` builds it for you. Entries queued by 0.1.x are still dispatched.
- **`Worker` rejects permanent failures.** It now accepts an `onRejected` callback for actions the server refused for good.
- **New HTTP layer.** Wire `HttpMailboxTransport(client.mailApi)` and `HttpDispatcher(client.mailApi, …)` into the sync engine and the outbox worker.

## 6. ProGuard / R8 Rules

ObjectBox and OkHttp need minimal ProGuard configuration. Add the following to `app/proguard-rules.pro`:

```proguard
# ObjectBox entity preservation
-keepclassmembers class * {
    @io.objectbox.annotation.Entity <fields>;
}
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**

# OkHttp 4 Rules
-dontwarn okhttp3.**
-dontwarn okio.**
```
