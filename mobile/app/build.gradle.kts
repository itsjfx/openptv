// `:app` — the only application module. Everything not specific to this module
// (compileSdk/minSdk, JVM target, Compose BOM, Hilt + KSP) is in
// `build-logic/convention/`. What stays here is genuinely app-specific:
// applicationId, version, build types, and the dependency surface the entry
// point needs.
plugins {
    id("openptv.android.application")
    id("openptv.android.application.compose")
    id("openptv.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ac.jfx.openptv"

    defaultConfig {
        applicationId = "ac.jfx.openptv"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Talk to the Go proxy running on the developer's host. `10.0.2.2` is the loopback
            // alias the Android emulator exposes for the host; on a physical device this
            // requires reverse-tethering (`adb reverse tcp:8080 tcp:8080`) or pointing at a
            // LAN-reachable host.
            buildConfigField("String", "BACKEND_BASE_URL", "\"http://10.0.2.2:8080/api/v3/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Placeholder until the hosted backend is up. HTTPS only — release builds do
            // not allow cleartext.
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api.openptv.app/api/v3/\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Hilt navigation-compose isn't part of `openptv.android.hilt` (it's a
    // Compose-only concern, not core Hilt) so feature modules pick it up via
    // `openptv.android.feature` and the app picks it up here.
    implementation(libs.androidx.hilt.navigation.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)

    // Coroutines / Time
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // Persistence
    implementation(libs.androidx.datastore.preferences)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
