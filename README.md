# Aduki Kotlin SDK

[![Maven Central](https://img.shields.io/maven-central/v/pro.aduki/sdk.svg)](https://central.sonatype.com/artifact/pro.aduki/sdk)
[![JitPack](https://jitpack.io/v/adukilabs/aduki-kt.svg)](https://jitpack.io/#adukilabs/aduki-kt)
[![Tests](https://github.com/adukilabs/aduki-kt/actions/workflows/ci.yml/badge.svg)](https://github.com/adukilabs/aduki-kt/actions/workflows/ci.yml)
[![Publish](https://github.com/adukilabs/aduki-kt/actions/workflows/publish.yml/badge.svg)](https://github.com/adukilabs/aduki-kt/actions/workflows/publish.yml)
[![Docs](https://img.shields.io/badge/docs-docs.aduki.pro%2Fkt-blue.svg)](https://docs.aduki.pro/kt)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple.svg?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-26%2B-green.svg?logo=android)](https://developer.android.com)

Kotlin client library for the [Aduki](https://aduki.pro) platform: sign-in at
Aduki ID (DPoP, OIDC client), offline-first mail, contacts and scheduling
sync, calls and meetings in Aduki Calls, verified senders, billing, and a
reactive state layer. Maven group `pro.aduki`; Apache 2.0.

1.0.0 is the first stable release: from here on, breaking changes come only in
a major version. Accounts are made on Aduki ID's sign-up page, not through the
SDK; see [Moving to 1.0.0](docs/start/migrate-1.0.md).

- **Use it:** the manual is in [`docs/`](docs/) (mdBook, published at `docs.aduki.pro/kt`).
- **Work on it:** see [`CONTRIBUTING.md`](CONTRIBUTING.md).
- **Publish & release:** see [`PUBLISH.md`](PUBLISH.md).

## Quick start

### Repositories

Artifacts are published to **Maven Central**, **GitHub Packages**, and **JitPack**:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // Or GitHub Packages:
        // maven {
        //     url = uri("https://maven.pkg.github.com/adukilabs/aduki-kt")
        //     credentials {
        //         username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        //         password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        //     }
        // }

        // Or JitPack:
        // maven { url = uri("https://jitpack.io") }
    }
}
```

### Dependencies

```kotlin
// build.gradle.kts
// Maven Central / GitHub Packages:
implementation("pro.aduki:sdk:1.0.0")
implementation("pro.aduki:android:1.0.0") // Android-specific features
implementation("io.objectbox:objectbox-android:5.4.2")

// Or JitPack:
// implementation("com.github.adukilabs.aduki-kt:sdk:v1.0.0")
// implementation("com.github.adukilabs.aduki-kt:android:v1.0.0")

// Sign in at Aduki ID; the 10-minute token is renewed automatically
val client = Aduki.login(handle = "ada@aduki.me", password = password, code = code)
client.watchRights()   // renew when the account's rights change

// Offline-first mail, contacts and scheduling on a local store
val app = Aduki.builder()
    .token(accessToken)
    .store(File(context.filesDir, "aduki"))
    .build()
app.sync.all()
app.mail.mailboxes()?.collect { boxes -> /* ... */ }
val body = app.mail.body(hex, BodyMode.Rich) // sanitised HTML when the server reads bodies

// Calls and meetings (a DPoP-bound session: builder().dpop(key))
val meeting = app.calls.meeting(title = "Weekly review")
share(meeting.link)                            // https://calls.aduki.pro/call/abc-defg-hij

// Sign-up happens on the web: open https://id.aduki.pro/signup
SignUpTab(back = "pro.aduki.mail:/signup").open(activity)

// Headless
val worker = Aduki.builder().key(apiKey).build()
```

See [Installation](docs/start/install.md) for the repositories and the module
artifacts.

## Build and test

```bash
./gradlew test          # every module (JDK 17+)
```

Android modules, live tests against a real server, conventions and releases:
see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Modules

`core`, `crypto`, `store`, `net`, `sync`, `state`, `sdk` (the `Aduki` facade),
and `android` (Keystore keys, the device account directory, the suite
sign-in broker and Accounts Center, Custom Tabs sign-in, passkeys). Since
0.8.0 `android` depends on `sdk`. See
[Suite sign-in & Accounts Center](docs/auth/suite.md).

## License

Apache License, Version 2.0.
