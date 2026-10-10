plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":crypto"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.stub)
    implementation(libs.json)


    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

// The live tier (LiveTest) against a running server: ADUKI_LIVE_URL,
// ADUKI_LIVE_EMAIL and ADUKI_LIVE_PASSWORD. Without them it is skipped,
// also in the regular `test` task.
tasks.register<Test>("liveTest") {
    description = "Runs the SDK against a live Aduki server (ADUKI_LIVE_*)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*LiveTest") }
    outputs.upToDateWhen { false }
}
