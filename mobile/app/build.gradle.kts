// `:app` — composition root. Owns `OpenPtvApplication` (`@HiltAndroidApp`),
// `MainActivity`, the top-level navigation graph, plus the bits not yet
// promoted out: `SettingsRepositoryImpl` (DataStore-backed) and the
// Setup / Settings screens. Those move to `:core:datastore` and
// `:feature:setup` / `:feature:settings` in the phases that introduce those
// modules.
//
// What lives here: `applicationId`, `versionCode`, `versionName`, build types
// (with `BACKEND_BASE_URL`). Everything else (compileSdk/minSdk, JVM target,
// Compose BOM, Hilt + KSP) comes from the convention plugins.
import java.util.Properties

plugins {
    id("openptv.android.application")
    id("openptv.android.application.compose")
    id("openptv.android.hilt")
    alias(libs.plugins.kotlin.serialization)
    // Dropbox Dependency Guard — locks `releaseRuntimeClasspath` (the set of
    // artifacts that ends up in the production APK) against a checked-in
    // baseline at `app/dependencies/releaseRuntimeClasspath.txt`. Run
    // `:app:dependencyGuard` to verify (CI gate) and
    // `:app:dependencyGuardBaseline` to rebaseline after intentional changes.
    // Deliberately applied only in `:app`, not via a convention plugin:
    // `:app` is the rollup that determines what actually ships, and
    // tracking each `:core:*` / `:feature:*` separately would just add churn.
    alias(libs.plugins.dependency.guard)
}

// Release signing credentials. Sourced from env vars (CI: decoded from the
// `KEYSTORE` secret + friends) or `local.properties` (a maintainer cutting a
// release on their laptop) — both are git-ignored, so no key material is ever
// committed. When any of the four is missing the release build is left
// UNSIGNED on purpose: CI's `assembleDebug`, the preview channel, and a plain
// `assembleRelease` on a fresh checkout all build fine without the keystore.
val signingProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun signingCredential(key: String): String? =
    System.getenv(key) ?: signingProperties.getProperty(key)

val releaseSigningCredentials: Map<String, String>? =
    listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .associateWith { signingCredential(it) }
        .takeIf { creds -> creds.values.all { !it.isNullOrBlank() } }
        ?.mapValues { it.value!! }

android {
    namespace = "ac.jfx.openptv"

    signingConfigs {
        releaseSigningCredentials?.let { creds ->
            create("release") {
                storeFile = file(creds.getValue("KEYSTORE_PATH"))
                storePassword = creds.getValue("KEYSTORE_PASSWORD")
                keyAlias = creds.getValue("KEY_ALIAS")
                keyPassword = creds.getValue("KEY_PASSWORD")
                // v1 (JAR) + v2/v3/v4 APK signature schemes; minSdk 26 covers v2.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "ac.jfx.openptv"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Custom runner lives in `:core:testing` and swaps the production `@HiltAndroidApp`
        // for `HiltTestApplication`. Required for any `@HiltAndroidTest`. Mirrors NIA's
        // `NiaTestRunner` wiring in `nowinandroid/app/build.gradle.kts`.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Same maintainer-operated proxy as release so the picker's "Default" radio
            // resolves to one URL regardless of variant. Developers running a local Go
            // proxy point the Custom field at `http://10.0.2.2:8080/api/v3/` (emulator
            // loopback) or `adb reverse tcp:8080 tcp:8080` on a physical device.
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://ptv.jfx.ac/api/v3/\"")
        }
        release {
            // Only signed when credentials are present (see above); otherwise
            // the APK comes out unsigned and must be signed out-of-band.
            if (releaseSigningCredentials != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Maintainer-operated proxy. HTTPS only — release builds do not allow cleartext.
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://ptv.jfx.ac/api/v3/\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// Scoped to `releaseRuntimeClasspath` only — that's the set of artifacts that
// ends up in the production APK and the one we actually need to lock down for
// the GrapheneOS / "no GMS sneaks in" guarantee. Debug / test configurations
// pull in MockWebServer, Hilt test infra, Compose tooling etc. and would add
// noisy baseline churn for no real shipping-code value.
dependencyGuard {
    configuration("releaseRuntimeClasspath")
}

dependencies {
    // Core libs — :app is a consumer of every core module it composes.
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:network"))

    // Features composed by the root nav graph.
    implementation(project(":feature:favourites"))
    implementation(project(":feature:nearby"))
    implementation(project(":feature:run-pattern"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:stop-detail"))

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

    // Serialization (root nav key)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines / Time
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // Persistence — `SettingsRepositoryImpl` is DataStore-backed and lives here
    // until `:core:datastore` lands in a later phase.
    implementation(libs.androidx.datastore.preferences)

    // Test
    testImplementation(project(":core:data-test"))
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    // androidTest classpath needs `:core:testing` so the `OpenPtvTestRunner` declared in
    // `testInstrumentationRunner` resolves at instrumented test time. No `androidTest`
    // source set lives in `:app` yet, but having the dependency in place means the first
    // androidTest someone adds will Just Work — same shape as NIA's `:app`.
    androidTestImplementation(project(":core:testing"))
}
