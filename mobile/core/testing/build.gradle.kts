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
}
