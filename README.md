# Aduki Kotlin SDK

Kotlin client library for the [Aduki](https://aduki.pro) platform: sign-in at
Aduki ID (DPoP, OIDC client), offline-first mail, contacts and scheduling
sync, and a reactive state layer. Maven group `pro.aduki`; Apache 2.0.

- **Use it:** the manual is in [`docs/`](docs/) (mdBook, published at `docs.aduki.pro/kt`).
- **Work on it:** see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Quick start

```kotlin
// build.gradle.kts
implementation("pro.aduki:sdk:0.5.0")
implementation("io.objectbox:objectbox-android:4.0.3")

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
and `android` (Keystore keys, account authenticator, Custom Tabs sign-in,
passkeys).

## License

Apache License, Version 2.0.
