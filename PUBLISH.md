# Publishing the Aduki Kotlin SDK

This document describes how to build, sign, and publish releases of the Aduki Kotlin SDK (`adukilabs/aduki-kt`) to **Maven Central**, **GitHub Packages**, and **JitPack**.

---

## 1. Distribution Channels

The SDK is distributed across three artifact repositories:

| Repository | Scope / Base URL | Coordinates |
| :--- | :--- | :--- |
| **Maven Central** (Canonical) | `https://repo1.maven.org/maven2/` | `pro.aduki:<module>:<version>` |
| **GitHub Packages** (Mirror) | `https://maven.pkg.github.com/adukilabs/aduki-kt` | `pro.aduki:<module>:<version>` |
| **JitPack** (On-Demand / Source) | `https://jitpack.io` | `com.github.adukilabs.aduki-kt:<module>:<version>` |

### Published Modules

Every release publishes 8 module artifacts (7 Kotlin/JVM jars and 1 Android AAR):

| Module | Type | Description |
| :--- | :--- | :--- |
| `:sdk` | JAR | The unified `Aduki` client facade |
| `:android` | AAR | Android Keystore keys, `AccountManager`, Custom Tabs sign-in, passkeys (`minSdk` 26) |
| `:state` | JAR | Repositories exposing Kotlin `StateFlow` |
| `:sync` | JAR | Incremental mailbox, contact, and schedule synchronizers & offline outbox |
| `:store` | JAR | Local ObjectBox storage entities and batch transactions |
| `:net` | JAR | HTTP clients for Aduki ID, Mail, Scheduling, Space, and Account Center |
| `:crypto` | JAR | AES-256-GCM cipher (`Vault`), DPoP signing (`Software`, `Device`), TLS pinning |
| `:core` | JAR | Domain models, error hierarchy (`AdukiException`), retry primitives (`Circuit`, `Jitter`) |

---

## 2. Prerequisites & Required Secrets

The automated publishing pipeline (`.github/workflows/publish.yml`) requires the following repository secrets configured in GitHub (`Settings -> Secrets and variables -> Actions`):

| Secret | Target | Description |
| :--- | :--- | :--- |
| `SIGNING_KEY` | Maven Central / GitHub Packages | ASCII-armored PGP private key used by Gradle `signing` plugin |
| `SIGNING_PASSWORD` | Maven Central / GitHub Packages | Passphrase protecting the PGP private key |
| `SONATYPE_USERNAME` | Maven Central | Sonatype Central Portal publisher user token username |
| `SONATYPE_PASSWORD` | Maven Central | Sonatype Central Portal publisher user token password |
| `GITHUB_TOKEN` | GitHub Packages | Automatically provided by Actions (`packages: write` permission) |
| `JITPACK_ACCESS_TOKEN` | JitPack | Personal access token from `https://jitpack.io/private` to trigger/authenticate builds |

---

## 3. Automated CI/CD Publishing Pipeline

The workflow defined in `.github/workflows/publish.yml` triggers automatically on:
- Pushing a version tag: `git push origin v*`
- Creating a GitHub Release in the repository
- Manual invocation via `workflow_dispatch`

### Pipeline Execution Flow

```
+-------------------------------------------------------------+
|                      1. Test Suite                          |
|  ./gradlew --no-daemon -Paduki.android=true test            |
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                   2. GitHub Packages                        |
|  ./gradlew --no-daemon -Paduki.android=true \               |
|    publishMavenPublicationToGithubRepository                |
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                   3. Maven Central Bundle                   |
|  ./gradlew --no-daemon -Paduki.android=true bundle          |
|  (Signs artifacts with PGP and packages build/bundle.zip)   |
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|              4. Maven Central Portal Upload                 |
|  POST https://central.sonatype.com/api/v1/publisher/upload  |
|  Polls deployment status until VALIDATED / PUBLISHED        |
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                     5. JitPack Trigger                      |
|  GET https://jitpack.io/api/builds/com.github.adukilabs/... |
|  Authenticated via JITPACK_ACCESS_TOKEN                     |
+-------------------------------------------------------------+
```

---

## 4. JitPack Configuration

JitPack builds the project from source using `jitpack.yml` at the repository root:

```yaml
jdk:
  - openjdk17

install:
  - ./gradlew --no-daemon -Paduki.android=true assemble publishToMavenLocal
```

- **JDK 17**: Required by Android Gradle Plugin (AGP) 8.13+ and Kotlin 2.0 compiler.
- **`-Paduki.android=true`**: Ensures the `:android` library is included in the build alongside JVM modules.
- **`publishToMavenLocal`**: Publishes all module publications to the runner's local Maven repository, which JitPack packages and serves.

---

## 5. Step-by-Step Release Checklist

When cutting a new release:

### 1. Bump Version
Update `release` in `build.gradle.kts`:
```kotlin
val release = "0.5.1"
```

### 2. Update Documentation
- Record user-facing changes and upgrade paths in `CHANGELOG.md`.
- Update version numbers in `README.md` and `docs/start/install.md`.
- Update centralized documentation in `adukilabs/docs`.

### 3. Verify Locally
Run a full test and bundle dry-run:
```bash
# Verify tests across all modules (including Android)
./gradlew --no-daemon -Paduki.android=true test

# Verify packaging and artifact signing
./gradlew --no-daemon -Paduki.android=true bundle
```
The bundle zip is written to `build/bundle.zip`.

### 4. Commit and Tag
```bash
git commit -s -m "chore: release 0.5.1"
git tag v0.5.1
git push origin main
git push origin v0.5.1
```

### 5. Monitor and Verify
1. **GitHub Actions**: Inspect the `publish` workflow run at `https://github.com/adukilabs/aduki-kt/actions`.
2. **GitHub Packages**: Confirm all 8 packages appear at `https://github.com/orgs/adukilabs/packages?repo_name=aduki-kt`.
3. **Maven Central**: Verify publication on Central Portal at `https://central.sonatype.com/artifact/pro.aduki/sdk`.
4. **JitPack**: Check build status at `https://jitpack.io/#adukilabs/aduki-kt/v0.5.1`.

---

## 6. Consumer Usage Guide

Downstream applications can consume the SDK through any of the three repositories.

### Option A: Maven Central (Recommended)

No extra repository credentials required.

#### Gradle Kotlin DSL (`settings.gradle.kts` & `app/build.gradle.kts`):
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("pro.aduki:sdk:0.5.1")
    implementation("pro.aduki:android:0.5.1") // Android-specific features
}
```

---

### Option B: GitHub Packages

Requires GitHub authentication via Personal Access Token (`read:packages` scope) or Actions `GITHUB_TOKEN`.

#### Gradle Kotlin DSL (`settings.gradle.kts` & `app/build.gradle.kts`):
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/adukilabs/aduki-kt")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("pro.aduki:sdk:0.5.1")
    implementation("pro.aduki:android:0.5.1")
}
```

---

### Option C: JitPack

Builds directly from repository tags or commits. For private access, authenticate with your JitPack access token.

#### Gradle Kotlin DSL (`settings.gradle.kts` & `app/build.gradle.kts`):
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            credentials {
                // JitPack expects authToken as username (e.g. from ~/.gradle/gradle.properties)
                username = providers.gradleProperty("authToken").orNull
                    ?: providers.gradleProperty("jitpack.token").orNull
                    ?: System.getenv("JITPACK_ACCESS_TOKEN")
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    // Coordinates use com.github.adukilabs.aduki-kt:<module>:<tag>
    implementation("com.github.adukilabs.aduki-kt:sdk:0.5.1")
    implementation("com.github.adukilabs.aduki-kt:android:0.5.1")
}
```

#### Gradle Groovy DSL (`settings.gradle` & `app/build.gradle`):
```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url 'https://jitpack.io'
            credentials { username = project.findProperty("authToken") ?: System.getenv("JITPACK_ACCESS_TOKEN") }
        }
    }
}

// app/build.gradle
dependencies {
    implementation 'com.github.adukilabs.aduki-kt:sdk:0.5.1'
    implementation 'com.github.adukilabs.aduki-kt:android:0.5.1'
}
```
