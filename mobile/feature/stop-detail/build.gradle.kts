// `:feature:stop-detail` — stop detail screen with stop header, route chips, and a live
// departures list that polls every 30 s while the lifecycle is RESUMED.
//
// `openptv.android.feature` wires the four standard core deps via `findProject(...)?.let { ... }`,
// so we don't repeat them here — the plugin picks up `:core:common`, `:core:designsystem`, and
// `:core:navigation`. We add `:core:data` (interfaces only — the network impl never crosses the
// feature boundary), `:core:model`, and `:core:domain` for the use cases.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.stopdetail"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — AGP wires
        // `testInstrumentationRunner` per-module from each library's own `defaultConfig`. Point
        // it at `OpenPtvTestRunner` (in `:core:testing`) so `@HiltAndroidTest` swaps in
        // `HiltTestApplication` for these androidTests. Mirrors `:feature:search`'s wiring.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }
}

dependencies {
    // Repository interface only — never the network impl. The use cases (in `:core:domain`) are
    // what the ViewModel touches; the repository projects exist on the api surface there.
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    // `LocalTimeFormat` + `rememberUse24Hour` for 12/24-hour clock-face rendering. Added in
    // #89; the absolute formatter logic itself lives in `:core:common` (already wired by the
    // `openptv.android.feature` plugin), this dep brings the Compose-bound resolver.
    implementation(project(":core:datastore"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // Retrofit's `HttpException` is what the ViewModel pattern-matches on when mapping errors to
    // user-facing strings (same compromise as `:feature:search`). The `DomainError` sealed type
    // that lifts this leak into `:core:common` is a follow-up.
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Hilt-instrumented Compose UI tests. `:ui-test-hilt-manifest` exports the
    // `HiltComponentActivity` host that `createAndroidComposeRule` launches; `:core:data-test`
    // brings `FakeStopDetailRepository` + `FakeDepartureRepository` and the `@TestInstallIn`
    // module that swaps them in for the production binding; `:core:testing` brings the
    // `StopDetailMother` / `DepartureMother` fixtures.
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
