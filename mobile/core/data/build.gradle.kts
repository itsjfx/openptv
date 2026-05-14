// `:core:data` — repository interfaces AND impls (NIA convention). Domain
// callers (use cases, ViewModels) depend on this module's `interface` types;
// the impls are wired via Hilt's `@Binds` modules.
//
// Allowed dependencies (per the docs/mobile/00-conventions.md rule):
//   :core:network, :core:database, :core:model, :core:common
//   (Datastore lands later; the favourites repository in #34 brought :core:database on.)
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.data"

    testOptions {
        // `FavouritesRepositoryImplTest` runs against an in-memory Room DB via Robolectric.
        // Room needs the manifest + AndroidX resources at runtime; this flag wires them through
        // the JVM unit-test classpath the same way `:core:database`'s DAO tests do.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:network"))
    // Favourites repository (issue #34) reads + writes Room via `FavouriteRouteAtStopDao`. The
    // dep is `implementation` — DAO and entity types stay internal to this module's repository
    // impl; consumers see only the domain `FavouriteRouteAtStop`.
    implementation(project(":core:database"))

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
    testImplementation(libs.turbine)
    // In-memory Room + Robolectric — same pairing the DAO tests use. Lets
    // `FavouritesRepositoryImplTest` exercise the real DAO instead of mocking it.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
