pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            // Only Android/Google artifacts come from Google's Maven, so a
            // JVM build never contacts it (some networks block it).
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
    }
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            // Only Android/Google artifacts come from Google's Maven, so a
            // JVM build never contacts it (some networks block it).
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
    }
}

rootProject.name = "aduki-kt"

include(":core")
include(":crypto")
include(":store")
include(":net")
include(":sync")
include(":state")
include(":sdk")


// The Android library needs the Android SDK and AGP (JDK 17). It joins the build
// only when asked (-Paduki.android=true or ADUKI_ANDROID=1) or when an SDK is
// configured, so the pure-JVM modules build on JDK 17 to 22 without Google's repository.
val withAndroid = providers.gradleProperty("aduki.android").orNull == "true" ||
    providers.environmentVariable("ADUKI_ANDROID").orNull == "1"
if (withAndroid) {
    include(":android")
    include(":sample")
}
