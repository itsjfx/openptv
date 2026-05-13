// `:feature:search` — single screen ("Search stops") and its ViewModel.
// `openptv.android.feature` wires the four standard core deps via
// `findProject(...)?.let { add(...) }`, so we don't repeat them here — the
// plugin picks up `:core:common`, `:core:designsystem`, and `:core:navigation`
// the moment those projects exist in `settings.gradle.kts` (`:core:ui` and
// `:core:domain` are deferred; the plugin is a no-op on missing modules).
//
// Per the conventions doc, feature modules never depend on `:core:network` or
// `:core:database`. The repository interface comes from `:core:data`.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.search"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — AGP
        // wires `testInstrumentationRunner` per-module from each library's own
        // `defaultConfig`. Point it at `OpenPtvTestRunner` (in `:core:testing`)
        // so `@HiltAndroidTest` swaps in `HiltTestApplication` for these
        // androidTests. Mirrors NIA's per-feature wiring.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }
}

dependencies {
    // Repository interface only — never the network impl.
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Retrofit is an internal detail of `:core:network` — features never see
    // it. Except `HttpException` is the type the ViewModel pattern-matches on
    // when mapping errors to user-facing strings. That's an unfortunate leak
    // we'll fix in a follow-up by introducing a `DomainError` sealed type in
    // `:core:common` and translating at the data-layer boundary.
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Hilt-instrumented Compose UI tests. `:ui-test-hilt-manifest` exports the
    // `HiltComponentActivity` host that `createAndroidComposeRule` launches;
    // `:core:data-test` brings `FakeStopSearchRepository` + the `@TestInstallIn`
    // module that swaps it in for the production binding; `:core:testing`
    // brings the `StopMother` fixtures. `debugImplementation` for the host
    // because AGP only merges debug manifests into androidTest APKs.
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
    // `ui-test-manifest` is the empty-manifest helper Compose needs at runtime
    // for `ComponentActivity`-hosted UI tests. Has to be on `debugImplementation`
    // so its manifest is merged into the test APK; placing it on
    // `androidTestImplementation` doesn't merge the manifest.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
