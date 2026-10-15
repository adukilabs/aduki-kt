// AGP must share a class loader with the Kotlin Gradle plugin, so it is put on the root build
// classpath, and only when the Android module is part of the build (-Paduki.android=true or
// ADUKI_ANDROID=1): a JVM-only build never contacts Google's Maven repository.
buildscript {
    val withAndroid = providers.gradleProperty("aduki.android").orNull == "true" ||
        providers.environmentVariable("ADUKI_ANDROID").orNull == "1"
    if (withAndroid) {
        repositories {
            mavenCentral()
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx.*")
                    includeGroupByRegex("com\\.google\\.android.*")
                    includeGroupByRegex("com\\.google\\.testing.*")
                }
            }
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.13.2")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.objectbox") version "4.0.3" apply false
    `maven-publish`
    signing
}

val release = "0.4.1-beta"
val domain = "pro.aduki"

// Java 17 bytecode whichever JDK (17 to 22) runs Gradle; Kotlin 2.0.x cannot target above 22.
val bytecode = 17

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(bytecode.toString()))
    }
    if (name != "android" && name != "sample") { // AGP owns the Java compile options of the Android library
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(bytecode)
        }
    }

    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    group = domain
    version = project.findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: release

    plugins.withId("org.jetbrains.kotlin.jvm") {
        configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])

                    pom {
                        name.set(project.name)
                        description.set("Aduki Android Kotlin SDK - ${project.name} module")
                        url.set("https://github.com/adukilabs/aduki-kt")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                id.set("adukilabs")
                                name.set("Aduki Labs")
                                email.set("dev@aduki.pro")
                            }
                        }
                        scm {
                            connection.set("scm:git:git://github.com/adukilabs/aduki-kt.git")
                            developerConnection.set("scm:git:ssh://github.com:adukilabs/aduki-kt.git")
                            url.set("https://github.com/adukilabs/aduki-kt")
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "github"
                    url = uri("https://maven.pkg.github.com/adukilabs/aduki-kt")
                    credentials {
                        username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                        password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                    }
                }
                maven {
                    name = "local"
                    url = uri(rootProject.layout.buildDirectory.dir("repo"))
                }
            }
        }

        configure<SigningExtension> {
            val key = System.getenv("SIGNING_KEY") ?: project.findProperty("signing.key") as String?
            val pass = System.getenv("SIGNING_PASSWORD") ?: project.findProperty("signing.password") as String?
            if (!key.isNullOrBlank()) {
                useInMemoryPgpKeys(key, pass ?: "")
                sign(extensions.getByType<PublishingExtension>().publications["maven"])
            }
        }
    }
}

tasks.register<Zip>("bundle") {
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "publishMavenPublicationToLocalRepository" } })
    from(layout.buildDirectory.dir("repo"))
    archiveFileName.set("bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}


