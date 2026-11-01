# Installation

This guide walks you through integrating the Aduki Android Kotlin SDK into your Android application using Gradle Kotlin DSL (`.gradle.kts`).

---

## 1. Prerequisites

- **JDK**: 17 or later (the SDK is Java 17 bytecode; it is built with JDK 21).
- **Kotlin**: 2.3 or later in the app (the SDK is compiled with Kotlin 2.4.21; a Kotlin compiler reads metadata up to one version ahead).
- **ObjectBox**: 5.4.2, with its Gradle plugin in the app that stores data (5.x is needed for 16 KB page-size devices).
- **OkHttp**: 5.5.1 (the SDK's `api` dependency).
- **Android**: `compileSdk` 37 (OkHttp 5's Android artifact requires it) and AGP 9.
- **Android**: the shared modules are plain JVM libraries (the Keystore `Provider` reaches Android classes by reflection and falls back to software keys elsewhere). The Android parts (Keystore DPoP key, account authenticator, Custom Tabs sign-in, Credential Manager) are in the `android` module, `minSdk` 26: see [Android module](android.md).

---

## 2. Configure Repositories

The SDK is published to **Maven Central**, **GitHub Packages**, and **JitPack**.

- **Maven Central** (default): No extra credentials required.
- **GitHub Packages**: Requires a GitHub personal access token with `read:packages` scope.
- **JitPack**: Builds directly from Git tags/commits (`com.github.adukilabs.aduki-kt`).

Add your preferred repository to `settings.gradle.kts`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        
        // Option B: GitHub Packages repository
        // maven {
        //     url = uri("https://maven.pkg.github.com/adukilabs/aduki-kt")
        //     credentials {
        //         username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        //         password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        //     }
        // }

        // Option C: JitPack repository
        // maven {
        //     url = uri("https://jitpack.io")
        //     credentials {
        //         username = providers.gradleProperty("authToken").orNull ?: providers.gradleProperty("jitpack.token").orNull ?: System.getenv("JITPACK_ACCESS_TOKEN")
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
    id("io.objectbox") version "5.4.2" apply false
}
```

In your application module `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) // AGP 9 compiles Kotlin itself
    id("io.objectbox") // Generates the entity bindings at compile time
}
```

---

## 4. Add Dependencies

Add the Aduki SDK to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Aduki SDK facade (Maven Central & GitHub Packages)
    implementation("pro.aduki:sdk:0.6.0")
    implementation("pro.aduki:android:0.6.0") // Android-specific features (optional)

    // Or via JitPack:
    // implementation("com.github.adukilabs.aduki-kt:sdk:0.6.0")
    // implementation("com.github.adukilabs.aduki-kt:android:0.6.0")

    // ObjectBox persistence
    implementation("io.objectbox:objectbox-kotlin:5.4.2")
    implementation("io.objectbox:objectbox-android:5.4.2")

    // Network
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
```

### Granular Module Artifacts

If your application only needs specific subsystems, you can import individual modules:

| Module | Maven Central / GitHub Packages | JitPack | Purpose |
| :--- | :--- | :--- | :--- |
| **SDK Facade** | `pro.aduki:sdk:0.6.0` | `com.github.adukilabs.aduki-kt:sdk:0.6.0` | The `Aduki` entry point |
| **Android** | `pro.aduki:android:0.6.0` | `com.github.adukilabs.aduki-kt:android:0.6.0` | Keystore DPoP key, account authenticator, Custom Tabs sign-in, passkeys (Android library, `minSdk` 26) |
| **State** | `pro.aduki:state:0.6.0` | `com.github.adukilabs.aduki-kt:state:0.6.0` | Repositories exposing `StateFlow` |
| **Sync** | `pro.aduki:sync:0.6.0` | `com.github.adukilabs.aduki-kt:sync:0.6.0` | RFC 7162 CONDSTORE synchronizer & Outbox |
| **Store** | `pro.aduki:store:0.6.0` | `com.github.adukilabs.aduki-kt:store:0.6.0` | ObjectBox entities |
| **Net** | `pro.aduki:net:0.6.0` | `com.github.adukilabs.aduki-kt:net:0.6.0` | HTTP clients (Aduki ID, mail, scheduling) |
| **Crypto** | `pro.aduki:crypto:0.6.0` | `com.github.adukilabs.aduki-kt:crypto:0.6.0` | Keystore provider, AES-256-GCM cipher, TLS pinning, DPoP keys |
| **Core** | `pro.aduki:core:0.6.0` | `com.github.adukilabs.aduki-kt:core:0.6.0` | models, errors, Jitter, Circuit, memory helpers |

---

## 5. Create the Client with a Local Store

Mail, contacts and scheduling are offline-first: they read from and write to a local ObjectBox database. Pass it to the builder with `store`, and the client builds everything on top of it with its own authenticated HTTP clients: the outbox manager and worker, the mail, contact and appointment repositories, and the mailbox, contact and schedule sync engines.

```kotlin
val client = Aduki.builder()
    .token(accessToken)
    .store(File(context.filesDir, "aduki")) // opened by build(), closed by client.close()
    .rejected { action, why -> notifySendFailed(action, why) } // optional
    .build()

client.sync.all()                                  // mailbox list, every mailbox, contacts
client.mail.mailboxes()?.collect { boxes -> /* ... */ }
```

`store(boxStore)` takes a `BoxStore` you opened yourself (for example with `Factory.build(dir)`); it stays yours and `client.close()` leaves it open. A part passed to the builder as well (`manager`, `worker`, `mail`, `contacts`, `scheduling`, `engines`, `contactStorage`) replaces the one the store would give.

Without a store, reads return `null` and writes (`mail.send`, `flag`, `move`, `remove`) throw `IllegalStateException("no local store")`.

---

## 6. Upgrading from 0.1.x

Version 0.3.0 also renames the packages to `pro.aduki.*`, the client to `Aduki` and the error type to `AdukiException`, and the Maven group to `pro.aduki` (it was `io.github.adukilabs`). There are no compatibility shims.

0.2.0 was a breaking release:

- **`Message` fields changed.** `snippet` is now `preview`, `date` is now `receivedAt`, and `from` is split into `fromName` and `fromEmail`. The new fields are `threadId`, `keywords`, `hasAttachment`, `sentAt` and `modseq`. The local store refills from the server on the next sync; the local store is rebuilt by a fresh sync.
- **Outbox payloads are JSON.** `Manager.send(msg, raw)` expects `raw` to be the JSON send request. `client.mail.send` builds it for you. Entries queued by 0.1.x are still dispatched.
- **`Worker` rejects permanent failures.** It now accepts an `onRejected` callback for actions the server refused for good.
- **New HTTP layer.** Wire `HttpMailboxTransport(client.mailApi)` and `HttpDispatcher(client.mailApi, …)` into the sync engine and the outbox worker.

## 7. ProGuard / R8 Rules

ObjectBox and OkHttp need minimal ProGuard configuration. Add the following to `app/proguard-rules.pro`:

```proguard
# ObjectBox entity preservation
-keepclassmembers class * {
    @io.objectbox.annotation.Entity <fields>;
}
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**

# OkHttp 5 ships its own R8 rules; these only silence optional platforms
-dontwarn okhttp3.**
-dontwarn okio.**
```
