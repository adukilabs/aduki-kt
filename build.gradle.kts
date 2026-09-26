plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.objectbox") version "4.0.3" apply false
    `maven-publish`
    signing
}

val release = "0.2.0"
val domain = "io.github.adukilabs"

subprojects {
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
                        description.set("Hermes Android Kotlin SDK - ${project.name} module")
                        url.set("https://github.com/adukilabs/hermers-kt")
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
                            connection.set("scm:git:git://github.com/adukilabs/hermers-kt.git")
                            developerConnection.set("scm:git:ssh://github.com:adukilabs/hermers-kt.git")
                            url.set("https://github.com/adukilabs/hermers-kt")
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "github"
                    url = uri("https://maven.pkg.github.com/adukilabs/hermers-kt")
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


