# Skill: build and test

Needs JDK 17. The Gradle wrapper (`./gradlew`, Gradle 9.6.0 per `gradle/wrapper/gradle-wrapper.properties`) downloads Gradle itself. No Android SDK is needed: every module is `kotlin("jvm")`.

```bash
cd /path/to/aduki-kt
java -version                         # must be 17+
df -h . ; free -m                     # Gradle and ObjectBox need disk and RAM
./gradlew --no-daemon test            # every module; contract tests replay net/src/test/resources/fixtures
./gradlew --no-daemon :net:test       # one module
./gradlew --no-daemon :net:test --tests '*IdTest*'   # one class (check the class name in the file)
```

Rules and gotchas:

- One build at a time, `--no-daemon`, never beside a cargo build, on an 8 GB box.
- Repositories: Maven Central and the Gradle plugin portal; Google's Maven is consulted only for `com.android*`, `androidx*`, `com.google.android*` (`settings.gradle.kts`). A network that blocks `dl.google.com` still builds.
- The `store` module uses the ObjectBox Gradle plugin (`io.objectbox` 4.0.3, resolved through `pluginManagement.resolutionStrategy`); the first build generates `MyObjectBox` and `store/objectbox-models/default.json`. Commit changes to `default.json` (see `add-entity-migration.md`).
- Live tests are skipped unless env vars are set (see `live-tier.md`), also inside `test`.
- Test reports: `<module>/build/reports/tests/test/index.html`.
- Build output (`build/`, `.gradle/`, `.kotlin/`) is gitignored; never commit it.

Verify: `BUILD SUCCESSFUL` and no failed test in the reports. If a test fails on slice 3 or 4 code (`id.kt`, `events.kt`, `dpop.kt`, `oidc.kt`), it was never compiled before: fix the code, not the test, unless the test contradicts its spec.
