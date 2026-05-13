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

    // `@TestInstallIn` annotations on `FakeDataModule` need `hilt-android-testing`
    // at compile time, but the annotations are an internal implementation detail
    // of this module — consumers in `:feature:*` androidTests bring their own
    // `hilt-android-testing` dependency (it's pulled in by `dagger.hilt.android.testing.HiltAndroidRule`).
    // NIA scopes the same dependency as `implementation` on its `:core:data-test`.
    implementation(libs.hilt.android.testing)
}
