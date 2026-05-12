// `:core:data-test` — hand-written fakes for repository interfaces, with
// `@TestInstallIn` modules that swap them in for every Hilt instrumented test
// across the codebase. The pattern is NIA-verbatim: feature androidTests
// declare `androidTestImplementation(project(":core:data-test"))` and the
// `@TestInstallIn` bindings activate automatically.
//
// Hilt is applied here because the `FakeDataModule` is a real Hilt module that
// KSP needs to process.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.data.test"
}

dependencies {
    api(project(":core:data"))
    api(project(":core:model"))
    api(project(":core:common"))

    // `@TestInstallIn` lives in `hilt-android-testing`, but consumers of this
    // module's fakes (instrumented + Robolectric tests in `:feature:*`) are
    // already pulling `hilt-android-testing` into their test classpath, so it
    // goes here as `api` rather than `implementation`.
    api(libs.hilt.android.testing)
}
