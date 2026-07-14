// `:feature:journey-planner` — direct services between two stops (issue #204): pick an origin
// and a destination, optionally a departure time, get the runs that connect them with no
// transfer. Polls every 30 s while the live "departing now" view is on screen, same cadence as
// stop-detail; a pinned custom time is a static snapshot (one-shot fetch, no polling).
//
// `openptv.android.feature` wires the standard core deps (`:core:common`, `:core:designsystem`,
// `:core:navigation`); we add `:core:data` (repository interfaces only), `:core:model`, and
// `:core:datastore` for the 12/24-hour clock-face preference the time chips honour.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.journeyplanner"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — point it at
        // `OpenPtvTestRunner` (in `:core:testing`) so `@HiltAndroidTest` swaps in
        // `HiltTestApplication` for these androidTests. Mirrors `:feature:search`.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))

    // Contextual POST_NOTIFICATIONS / location requests for the result-row alight bell
    // (issue #220) — same plumbing as :feature:run-pattern.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // Retrofit's `HttpException` is what the ViewModel pattern-matches on when mapping errors
    // to user-facing strings (same compromise as `:feature:search`; the `DomainError` lift is a
    // codebase-wide follow-up).
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Hilt-instrumented Compose UI tests — same shape as `:feature:search`.
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
