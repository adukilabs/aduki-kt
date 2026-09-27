plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    api(project(":crypto"))
    api(project(":store"))
    api(project(":net"))
    api(project(":sync"))
    implementation(libs.json)
    api(project(":state"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.okhttp)


    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

