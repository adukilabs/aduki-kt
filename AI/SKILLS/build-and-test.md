# Skill: build and test

Needs JDK 17. The Gradle wrapper (`./gradlew`, Gradle 8.13 per `gradle/wrapper/gradle-wrapper.properties`) downloads Gradle itself. No Android SDK is needed for the JVM modules. The Android library `android` and the `sample` app join the build only with `-Paduki.android=true` (or `ADUKI_ANDROID=1`) and need the Android SDK (`ANDROID_HOME`: platform 34, build-tools, platform-tools; AGP downloads build-tools 35 too).

```bash
cd /path/to/aduki-kt
java -version                         # must be 17+
df -h . ; free -m                     # Gradle and ObjectBox need disk and RAM
./gradlew --no-daemon test            # every module; contract tests replay net/src/test/resources/fixtures
./gradlew --no-daemon :net:test       # one module
./gradlew --no-daemon :net:test --tests '*IdTest*'   # one class (check the class name in the file)
```

Android (needs `ANDROID_HOME`, JDK 17 to 22, network to `dl.google.com` and Maven Central; Robolectric downloads its `android-all` jar into `~/.m2`):

```bash
./gradlew --no-daemon test -Paduki.android=true                 # JVM modules + Robolectric tests (debug and release)
./gradlew --no-daemon :android:connectedDebugAndroidTest -Paduki.android=true   # emulator or phone attached
./gradlew --no-daemon :sample:assembleDebug -Paduki.android=true -PadukiClient=<id>   # manual-test app
```

Emulator without KVM: `emulator -avd <name> -no-accel -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot` boots an API 34 x86_64 AOSP image in about 5 minutes; run it with `hide_error_dialogs` and do not build beside it (ANR dialogs). The two-phase restart test is in `guide/ANDROID-TESTS.md` section 4.

Rules and gotchas:

- Gradle wrapper is 8.13 (compatible with AGP 8.13.2 and Kotlin 2.0 maven-publish). AGP sits on the root build classpath only with `-Paduki.android=true` (it must share a class loader with the Kotlin plugin).
- One build at a time, `--no-daemon`, never beside a cargo build, on an 8 GB box.
- Repositories: Maven Central and the Gradle plugin portal; Google's Maven is consulted only for `com.android*`, `androidx*`, `com.google.android*` (`settings.gradle.kts`). A network that blocks `dl.google.com` still builds.
- The `store` module uses the ObjectBox Gradle plugin (`io.objectbox` 4.0.3, resolved through `pluginManagement.resolutionStrategy`); the first build generates `MyObjectBox` and `store/objectbox-models/default.json`. Commit changes to `default.json` (see `add-entity-migration.md`).
- Live tests are skipped unless env vars are set (see `live-tier.md`), also inside `test`.
- Test reports: `<module>/build/reports/tests/test/index.html`.
- Build output (`build/`, `.gradle/`, `.kotlin/`) is gitignored; never commit it.

Verify: `BUILD SUCCESSFUL` and no failed test in the reports. If a test fails on slice 3 or 4 code (`id.kt`, `events.kt`, `dpop.kt`, `oidc.kt`), it was never compiled before: fix the code, not the test, unless the test contradicts its spec.
