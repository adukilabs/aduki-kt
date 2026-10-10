# Aduki Kotlin SDK

Kotlin client library for the [Aduki](https://aduki.pro) platform: sign-in at
Aduki ID (DPoP, OIDC client), offline-first mail, contacts and scheduling
sync, and a reactive state layer. Maven group `pro.aduki`; Apache 2.0.

- **Use it:** the public manual is in [`docs/`](docs/) (mdBook, published at `docs.aduki.pro/kt`). Build it with `mdbook build docs`.
- **Work on it:** internal plans, design notes and progress are in [`guide/`](guide/README.md).
- **AI on the hosting server:** start at [`AI/README.md`](AI/README.md); what remains to verify is in [`AI/PROGRESS.md`](AI/PROGRESS.md).

## Quick start

```kotlin
// build.gradle.kts
implementation("pro.aduki:sdk:0.4.0")
implementation("io.objectbox:objectbox-android:4.0.3")

// Sign in at Aduki ID; the 10-minute token is renewed automatically
val client = Aduki.login(handle = "ada@aduki.me", password = password, code = code)
client.watchRights()   // renew when the account's rights change

// Headless
val worker = Aduki.builder().key(apiKey).build()
```

See [Installation](docs/start/install.md) for the repositories and the module
artifacts.

## Build and test

```bash
./gradlew test          # every module; contract tests replay the server's fixtures
./gradlew :net:liveTest # the live tier, against a running Aduki server
```

The build is plain JVM and needs Maven Central and the Gradle plugin portal
only (Google's Maven is used for Android and Google groups alone). The live
tier runs only when `ADUKI_LIVE_URL`, `ADUKI_LIVE_EMAIL`, `ADUKI_LIVE_PASSWORD`
and a second factor (`ADUKI_LIVE_CODE` or `ADUKI_LIVE_BACKUP`) are set; it sends and deletes real mail on that account.
Details in [`AI/SKILLS/build-and-test.md`](AI/SKILLS/build-and-test.md).

## Modules

`core`, `crypto`, `store`, `net`, `sync`, `state`, `sdk` (the `Aduki` facade).

## License

Apache License, Version 2.0.
