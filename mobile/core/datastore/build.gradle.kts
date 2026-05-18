// `:core:datastore` — typed `Preference` DSL on top of Preferences DataStore
// (Phase 4 per docs/mobile/phase-04-favourites.md). Owns:
//   - the sealed `Preference<T>` hierarchy + per-setting subtypes
//     (`ThemeModePreference`, `DynamicColourPreference`, `FavouritesSortPreference`),
//   - one `compositionLocalOf { default }` per preference,
//   - the `SettingsProvider` Composable that wraps app content at the root and
//     pushes every collected value down through `CompositionLocalProvider`,
//   - the Hilt `UserPreferencesDataStore` singleton for non-Compose consumers
//     (workers, ViewModels that need to write).
//
// Applies `openptv.android.library.compose` because the module exposes a
// Composable (`SettingsProvider`) — Compose runtime + foundation come from the
// shared Compose configuration so the BOM pins line up with every other
// Compose-aware module. Material3 is pulled in by the shared Compose
// configuration but `:core:datastore` itself stays UI-agnostic; the locals do
// not depend on Material types.
//
// Allowed dependencies (per docs/mobile/00-conventions.md):
//   - `:core:model` — needed by `MapRouteTypeFilterPreference` (issue #112) to persist
//     `Set<RouteType>`. `:core:model` is itself leaf-level (pure data classes, no Android),
//     so the "leaf-level infrastructure" property is preserved.
//   - no `:core:data` / `:core:network` — preferences must stay self-contained so every
//     later feature can depend on the DSL without dragging in the data / network layers.
plugins {
    id("openptv.android.library")
    id("openptv.android.library.compose")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.datastore"

    // Robolectric needs `includeAndroidResources = true` so the Compose UI test runner can
    // resolve the manifest + resources for `createComposeRule`. Same shape as
    // `:core:database`'s test options.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
