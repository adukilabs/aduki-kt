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
            }
        }
    }
}

rootProject.name = "hermes-android"

include(":core")
include(":crypto")
include(":store")
include(":net")
include(":sync")
include(":state")
include(":sdk")

