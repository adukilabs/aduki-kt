# Contributing to the Aduki Kotlin SDK

Thanks for working on the SDK. This page covers building, testing, the code
conventions, changing stored entities, documentation and releases.

## Build and test

You need JDK 17 or later. The Gradle wrapper (`./gradlew`) downloads Gradle
itself. The JVM modules need no Android SDK. Only Maven Central and the Gradle
plugin portal are required: Google's Maven is consulted for Android and Google
groups alone.

```bash
./gradlew test                                  # every module
./gradlew :net:test                             # one module
./gradlew :net:test --tests 'pro.aduki.net.http.MailTest'   # one class
```

Test reports are written to `<module>/build/reports/tests/test/index.html`.

### Android

The `android` library and the `sample` app join the build only with
`-Paduki.android=true` (or `ADUKI_ANDROID=1`). They need the Android SDK
(`ANDROID_HOME`, platform 34) and JDK 17 to 22.

```bash
./gradlew test -Paduki.android=true                                   # JVM modules and Robolectric tests
./gradlew :android:connectedDebugAndroidTest -Paduki.android=true     # Keystore tests on a device or emulator
./gradlew :sample:assembleDebug -Paduki.android=true \
    -PadukiIssuer=https://id.aduki.pro -PadukiClient=<OIDC client id> -PadukiScheme=pro.aduki.sample
```

The `sample` app has one button per check (device key, sign-in, accounts,
passkeys, DPoP calls) and logs results without tokens or keys. Keystore key
survival across a force stop is checked in two phases:

```bash
./gradlew :android:installDebug :android:installDebugAndroidTest -Paduki.android=true
adb shell am instrument -w -e class pro.aduki.android.KeystoreInstrumentedTest#survivalPhase1CreateKeysAndData \
    pro.aduki.android.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop pro.aduki.android.test
adb shell am instrument -w -e class pro.aduki.android.KeystoreInstrumentedTest#survivalPhase2AfterForceStopKeysStillWork \
    pro.aduki.android.test/androidx.test.runner.AndroidJUnitRunner
```

### Live tests

Live tests run against a real Aduki server and send real mail, so use a
dedicated test account. They are skipped unless configured. Export
credentials in your shell only; never write them to a file or a log.

- `sdk` (`LiveTest`): `ADUKI_LIVE=true`, plus `ADUKI_TOKEN` (an Aduki ID access
  token for the `mail` audience) or `ADUKI_KEY` (an API key).
  - Optional: `ADUKI_ENDPOINT` (default `https://mail.aduki.pro/v1`), and
    `ADUKI_ADDRESS` (the account's own address, for the send tests).
  - Run with `./gradlew :sdk:test --tests 'pro.aduki.sdk.LiveTest' --rerun`.
- `net` (`./gradlew :net:liveTest`): `ADUKI_LIVE_URL`, `ADUKI_LIVE_EMAIL`,
  `ADUKI_LIVE_PASSWORD`, and a second factor (`ADUKI_LIVE_CODE` or
  `ADUKI_LIVE_BACKUP`).
  - Optional: `ADUKI_LIVE_ID` (the Aduki ID base).
  - Authenticator codes expire in 30 seconds and backup codes are single use.

Live tests clean up what they create.

## Code conventions

**Naming**
- One word first: `created`, not `createdAt`; `active`, not `isActive`; `tenant`, not `tenantId`.
- Drop qualifiers the scope already supplies: `Center.link`, not `Center.linkAccount`.
- Functions are one verb (`fetch`, `save`, `remove`, `send`). A second word is a noun (`fetchAll`). Booleans are adjectives (`active()`).
- Variables name what the value is (`user`, not `fetchedUser`), with no type suffixes.
- Files and folders are lowercase, one word, plural for collections, with the domain in the folder (`http/login.kt`).

**Design**
- A module's public surface is what its package exposes; mark the rest `internal`. Never reach into another module's internals.
- Persistence stays in `store` and transport in `net`. `sdk` composes them and never touches ObjectBox directly.
- Imports are explicit: no wildcards, and no renaming except to settle a real name collision.
- A function with more than three parameters takes a typed request class with `validate()`.
- Every HTTP reply is read through the envelope reader (`net.http.Envelope`). Never trust a 2xx without its required fields.
- Tokens and keys live in the Keystore or `Guard` buffers, are wiped after use, and are never logged.
- Offline-first writes are applied to the local store and queued in the outbox. A retry must never repeat a side effect (idempotency keys, stable ids).

**Performance**
- Define the metric and the budget first, and measure before changing anything.
- Put before and after numbers in the pull request.
- Battery is a budget too: batch network work, use jittered retries, and never poll where a push exists.

## Changing a stored entity

ObjectBox has no SQL migrations. The model is the `@Entity` classes in
`store/src/main/kotlin/pro/aduki/store/entities/` plus
`store/objectbox-models/default.json`, which maps names to stable ids.

1. Edit the entity. Add a field with a default; a rename needs `@Uid`,
   otherwise it is a delete plus an add.
2. Run `./gradlew :store:build` to regenerate the model, and commit the
   changed `default.json`. Never hand-edit ids, except to resolve a merge
   conflict: two branches that each add an entity or index get the same id, and
   one side must take the next free id with a new uid.
3. Personal text columns are sealed (`SealedText`, `SealedList`); keep new
   ones sealed and list them in `Sealing`.
4. Check every code path that builds the entity (sync engines, outbox,
   repositories, tests), and update `docs/store/entities.md`.

## Documentation

The manual in `docs/` is an mdBook published at `docs.aduki.pro/kt`. Change
the code and the page that describes it in the same pull request. Check
signatures against the source. Make no performance or security claim that a
test or measurement does not back. Add new pages to `docs/SUMMARY.md` and
build with `mdbook build docs` (the output in `docs/book/` is not committed).
Record user-visible changes in `CHANGELOG.md` under `Unreleased`.

## Pull requests

- Commits are signed off (`git commit -s`) and use conventional messages (`feat(mail): ...`, `fix(sync): ...`).
- No secrets, tokens, real addresses or default credentials in code, tests, fixtures or docs.
- Every route or behaviour gets a test: MockWebServer or recorded fixtures for `net`, a real ObjectBox store for sync and state.
- Never force-push `main`.

## Releasing

1. Set the version (`val release = "x.y.z"` in the root `build.gradle.kts`),
   and update the version strings in `README.md` and `docs/start/install.md`.
2. Dry run without uploading: `./gradlew test bundle`. It writes
   `build/bundle.zip`, unsigned unless `SIGNING_KEY` is set.
3. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`. The `publish`
   workflow tests, signs, publishes to GitHub Packages and uploads to Maven
   Central. It needs the repository secrets `SIGNING_KEY`, `SIGNING_PASSWORD`,
   `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`.
4. Check that `pro.aduki:sdk:X.Y.Z` resolves from a clean project.
