// `:feature:favourites` — list of starred route-at-stop entries with next-departure subtext,
// drag-to-reorder, swipe-to-delete with undo, and a Manual / Alphabetical / Nearest sort
// selector. Implements issue #35.
//
// `openptv.android.feature` wires the four standard core deps via `findProject(...)?.let { ... }`,
// so the plugin picks up `:core:common`, `:core:designsystem`, and `:core:navigation` for us. We
// add `:core:data` (interfaces — for `DepartureRepository` via the use case), `:core:domain` (for
// `ObserveFavouritesUseCase`, `ReorderFavouritesUseCase`, `LoadNextDepartureUseCase`),
// `:core:model`, and `:core:datastore` (writes to `FavouritesSortPreference`, reads
// `LocalFavouritesSort`).
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.favourites"

    defaultConfig {
        // Library modules don't inherit `:app`'s instrumentation runner — AGP wires
        // `testInstrumentationRunner` per-module from each library's own `defaultConfig`. Point
        // it at `OpenPtvTestRunner` (in `:core:testing`) so `@HiltAndroidTest` swaps in
        // `HiltTestApplication` for these androidTests. Mirrors `:feature:stop-detail`.
        testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"
    }

    // Robolectric (used by `FavouritesScreenKeyTest`) needs the manifest + resources from the
    // module on the test classpath. Same shape as `:core:datastore` / `:core:database`.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Repository interface only — never the network impl. The use cases (in `:core:domain`) are
    // what the ViewModel touches.
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))

    // DataStore leaks through `UserPreferencesDataStore.dataStore` (typed DSL's
    // `put(scope, dataStore)` requires the underlying store as an argument). Same pattern as
    // `:feature:settings`.
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Robolectric gives JVM tests a shadowed `android.os.Bundle` that enforces the real type
    // whitelist. Used by `FavouritesScreenKeyTest` to pin the Bundle-safe `LazyColumn` key
    // contract without booting an emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // Hilt-instrumented Compose UI tests. Same wiring as `:feature:stop-detail` /
    // `:feature:settings`.
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
