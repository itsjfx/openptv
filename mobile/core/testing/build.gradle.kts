// `:core:testing` — shared test fixtures (Object Mothers, fake clocks, stable
// fixtures). Sits in `src/main/` so tests in `:core:*` / `:feature:*` can depend
// on it via `testImplementation(project(":core:testing"))` and pull the same
// builders into both unit and instrumented tests.
//
// Android library (rather than `openptv.jvm.library`) because future test
// fixtures may need Android types — `Context`, `Uri`, `Bundle`. For now the
// mothers we ship are pure Kotlin, but the boundary doesn't cost us anything.
plugins {
    id("openptv.android.library")
}

android {
    namespace = "ac.jfx.openptv.core.testing"
}

dependencies {
    api(project(":core:model"))

    // `MainDispatcherRule` exposes `TestDispatcher` in its constructor, so the coroutines-test
    // surface is part of this module's public API — `api` rather than `implementation` so
    // consumers can construct the rule without re-declaring the dependency.
    api(libs.kotlinx.coroutines.test)

    // `OpenPtvTestRunner` lives in `src/main/` because the `:app` module's
    // `testInstrumentationRunner` setting resolves it on the main classpath,
    // not the test classpath — same shape as NIA's `NiaTestRunner`.
    implementation(libs.androidx.test.runner)
    implementation(libs.hilt.android.testing)
}
