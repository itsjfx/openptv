// `:feature:settings` — Appearance settings screen (theme mode + dynamic
// colour). First real consumer of the typed Preference DSL in `:core:datastore`.
// `openptv.android.feature` wires the four standard core deps via
// `findProject(...)?.let { add(...) }`, so we don't repeat them here — the
// plugin picks up `:core:common`, `:core:designsystem`, and `:core:navigation`
// automatically.
//
// Per the conventions doc, feature modules never depend on `:core:network` or
// `:core:database`. Writes go through `:core:datastore`'s typed DSL.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.settings"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — AGP
        // wires `testInstrumentationRunner` per-module from each library's own
        // `defaultConfig`. Point it at `OpenPtvTestRunner` (in `:core:testing`)
        // so `@HiltAndroidTest` swaps in `HiltTestApplication` for these
        // androidTests. Mirrors `:feature:search`.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }
}

dependencies {
    // The DSL + Hilt-bound singleton facade. Reads are via the composition
    // locals (`LocalThemeMode`, `LocalDynamicColour`) — wired by `SettingsProvider`
    // at the app root. Writes are via the typed `Preference.put(scope, dataStore)`
    // off of `UserPreferencesDataStore` injected into the ViewModel.
    implementation(project(":core:datastore"))

    // DataStore types leak through `UserPreferencesDataStore.dataStore` (the typed
    // DSL's `put(scope, dataStore)` requires the underlying store as an argument).
    // Pulled in directly here rather than `api`-exporting it from `:core:datastore`
    // so the dep stays explicit at every consumer.
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Hilt-instrumented Compose UI tests. `:ui-test-hilt-manifest` exports the
    // `HiltComponentActivity` host that `createAndroidComposeRule` launches;
    // `:core:testing` brings the dispatcher rule. `debugImplementation` for the
    // host because AGP only merges debug manifests into androidTest APKs.
    debugImplementation(project(":ui-test-hilt-manifest"))
    androidTestImplementation(project(":core:testing"))
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
