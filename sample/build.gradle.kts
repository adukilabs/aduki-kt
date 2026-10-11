plugins {
    id("com.android.application")
    kotlin("android")
}

// The sample is the manual-test app of guide/ANDROID-TESTS.md. Configure it with Gradle properties:
//   -PadukiIssuer=https://id.aduki.pro  -PadukiClient=<OIDC client id>  -PadukiScheme=pro.aduki.sample
// The OIDC client must be registered at Aduki ID with the redirect "<scheme>:/callback" (public client, PKCE).
val issuer = providers.gradleProperty("adukiIssuer").orElse("https://id.aduki.pro")
val clientId = providers.gradleProperty("adukiClient").orElse("CHANGE-ME")
val scheme = providers.gradleProperty("adukiScheme").orElse("pro.aduki.sample")

android {
    namespace = "pro.aduki.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "pro.aduki.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.4.0"
        manifestPlaceholders["adukiRedirectScheme"] = scheme.get()
        buildConfigField("String", "ISSUER", "\"${issuer.get()}\"")
        buildConfigField("String", "CLIENT", "\"${clientId.get()}\"")
        buildConfigField("String", "SCHEME", "\"${scheme.get()}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation(project(":android"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.okhttp)
}
