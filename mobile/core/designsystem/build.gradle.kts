// `:core:designsystem` — owns the Compose theme entry point (`OpenPtvTheme`)
// and (eventually) the palette + typography. Applies both the library and
// library-compose convention plugins so it gets the Compose BOM pinning.
plugins {
    id("openptv.android.library")
    id("openptv.android.library.compose")
}

android {
    namespace = "ac.jfx.openptv.core.designsystem"

    // Robolectric drives `createComposeRule` in any JVM Compose smoke tests added here; same
    // shape as `:core:datastore` / `:feature:favourites` use.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Compose deps come from `openptv.android.library.compose`. ReadYou palette / typography
    // land in the phase that ships them.

    // The shared custom-time selector (issue #182) speaks `kotlinx.datetime.Instant` /
    // `LocalDateTime` at its public seam so feature modules pass domain time types straight in
    // without converting to `java.time` at the boundary.
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
