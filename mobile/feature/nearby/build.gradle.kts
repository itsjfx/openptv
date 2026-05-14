// `:feature:nearby` — Phase 05 map screen with MapLibre + OpenFreeMap tiles, clustered stop pins,
// follow-me FAB, and a tap-pin bottom sheet that links to stop detail. Implements issue #37.
//
// `openptv.android.feature` wires the four standard core deps via `findProject(...)?.let { ... }`,
// so the plugin picks up `:core:common`, `:core:designsystem`, and `:core:navigation`. We add
// `:core:data` (NearbyStopsRepository interface), `:core:model`, and MapLibre Android.
//
// MapLibre choice: the issue + the phase-05 doc named `org.maplibre.compose:maplibre-compose`,
// which doesn't exist as a published artifact today. The closest viable bindings are
// `org.ramani-maps:ramani-maplibre` (MPL-2.0) and the raw MapLibre Android SDK
// (`org.maplibre.gl:android-sdk`, BSD-2). We picked the raw SDK + an `AndroidView` wrap inside
// `OpenPtvMap` because:
//   - The spec demands a thin wrapper accepting only domain types anyway (test-seam requirement);
//     pulling in a third-party Compose wrapper would mean wrapping a wrapper.
//   - One fewer transitive dep on the runtime classpath = simpler `:app:dependencyGuard` baseline.
//   - BSD-2 is fewer license-compatibility questions than MPL-2.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.nearby"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — AGP wires
        // `testInstrumentationRunner` per-module from each library's own `defaultConfig`. Point
        // it at `OpenPtvTestRunner` (in `:core:testing`) so `@HiltAndroidTest` swaps in
        // `HiltTestApplication` for these androidTests. Mirrors `:feature:favourites`.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }
}

dependencies {
    // Repository interface only — never the network impl. Mirrors `:feature:favourites`.
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    // OkHttp goes onto MapLibre's HTTP stack via `HttpRequestUtil.setOkHttpClient(...)` so the
    // tile/style fetch hits our 50 MiB HTTP cache. The cache lives behind a `@MapsCache`
    // qualifier; see `MapsCacheModule`.
    implementation(libs.okhttp)

    implementation(libs.maplibre.android.sdk)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Hilt-instrumented Compose UI tests. Same wiring as `:feature:favourites` /
    // `:feature:stop-detail`.
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
