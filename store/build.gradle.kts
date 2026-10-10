plugins {
    kotlin("jvm")
    id("io.objectbox")
}

dependencies {
    implementation(project(":core"))
    api(project(":crypto"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.objectbox.kotlin)

    testImplementation(libs.junit)
}

