plugins {
    kotlin("jvm")
    id("io.objectbox")
}

dependencies {
    implementation(project(":core"))
    api(project(":crypto"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutines.core)
    // BoxStore is in the SDK's public API (Aduki.Builder.store); ObjectBox 5's plugin no longer exports it.
    api(libs.objectbox.java)
    api(libs.objectbox.kotlin)

    testImplementation(libs.junit)
}

