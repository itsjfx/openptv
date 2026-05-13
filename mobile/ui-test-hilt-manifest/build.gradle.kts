// `:ui-test-hilt-manifest` — minimal NIA-style module that exports a single
// `@AndroidEntryPoint ComponentActivity`. Feature androidTests
// `debugImplementation(project(":ui-test-hilt-manifest"))` and use it as the
// host for `createAndroidComposeRule<HiltComponentActivity>()`.
//
// `openptv.android.hilt` is applied because the activity itself is
// `@AndroidEntryPoint` — Hilt's KSP processor needs to see it.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.uitesthiltmanifest"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
