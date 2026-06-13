// `:feature:run-pattern` — the stopping pattern of a single service run (issue #132), reached by
// tapping a departure row on stop-detail. Polls every 30 s while RESUMED, same cadence as the
// departures list.
//
// `openptv.android.feature` wires the standard core deps (`:core:common`, `:core:designsystem`,
// `:core:navigation`); we add `:core:data` (interfaces only), `:core:model`, `:core:domain` for
// the use case, and `:core:datastore` for the 12/24-hour clock-face preference.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.runpattern"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — point it at
        // `OpenPtvTestRunner` (in `:core:testing`) so `@HiltAndroidTest` swaps in
        // `HiltTestApplication` for these androidTests. Mirrors `:feature:stop-detail`.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // Retrofit's `HttpException` is what the ViewModel pattern-matches on when mapping errors to
    // user-facing strings (same compromise as `:feature:stop-detail`).
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Hilt-instrumented Compose UI tests — same shape as `:feature:stop-detail`.
    debugImplementation(project(":ui-test-hilt-manifest"))
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(project(":core:data-test"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
