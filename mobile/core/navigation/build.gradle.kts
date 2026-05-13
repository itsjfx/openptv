// `:core:navigation` — Navigation 3 `NavKey` definitions for cross-feature
// navigation. Lives in its own module so a `:feature:*` that wants to navigate
// to another feature's destination just imports the key, not the destination's
// composable.
//
// Pure-Kotlin would be enough today (the keys are just `@Serializable data
// object`s), but Navigation 3 deps require Android, and reach for the
// `androidx.navigation3.runtime.NavKey` marker interface. So this is an Android
// library module despite holding only data.
plugins {
    id("openptv.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ac.jfx.openptv.core.navigation"
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.kotlinx.serialization.json)
}
