// `:core:data` — repository interfaces AND impls (NIA convention). Domain
// callers (use cases, ViewModels) depend on this module's `interface` types;
// the impls are wired via Hilt's `@Binds` modules.
//
// Allowed dependencies (per the docs/mobile/00-conventions.md rule):
//   :core:network, :core:model, :core:common
// Never `:core:database` (Phase 6) or `:core:datastore` (later) because those
// modules don't exist yet — when they do, this module will gain the right
// `implementation(project(":core:database"))` line.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:network"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    // Reuses `FakeSettingsRepository` from `:core:data-test`. Pulling
    // `:core:data-test` into a unit-test classpath of the same module it provides fakes for
    // is the same shape as NIA's `core/data` unit tests, which `testImplementation` the
    // `:core:data-test` module they emit.
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
