// `:core:designsystem` — owns the Compose theme entry point (`OpenPtvTheme`)
// and (eventually) the palette + typography. Applies both the library and
// library-compose convention plugins so it gets the Compose BOM pinning.
plugins {
    id("openptv.android.library")
    id("openptv.android.library.compose")
}

android {
    namespace = "ac.jfx.openptv.core.designsystem"

    // Robolectric drives `createComposeRule` in `LocationPermissionRationaleTest`; same
    // shape as `:core:datastore` / `:feature:favourites` use for JVM Compose smoke tests.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Compose deps come from `openptv.android.library.compose`. ReadYou palette / typography
    // land in the phase that ships them.

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
