// `:core:data` — repository interfaces AND impls (NIA convention). Domain
// callers (use cases, ViewModels) depend on this module's `interface` types;
// the impls are wired via Hilt's `@Binds` modules.
//
// Allowed dependencies (per the docs/mobile/00-conventions.md rule):
//   :core:network, :core:database, :core:datastore, :core:model, :core:common
//   (The favourites repository in #34 brought :core:database on; the followed-trip
//   repository in #200 brought :core:datastore on.)
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
    // Followed-trip repository (issue #200) delegates persistence to
    // `FollowedTripDataSource`. `implementation` — the datastore types stay internal to the
    // impl; consumers see only the domain `FollowedTrip` behind the repository interface.
    implementation(project(":core:datastore"))

    implementation(libs.kotlinx.coroutines.android)
    // `LocationManagerLocationProvider` uses `androidx.core.content.ContextCompat.checkSelfPermission`
    // to absorb pre-grant permission checks without throwing a `SecurityException`. Declared
    // explicitly rather than relying on a transitive (Hilt / Compose already pull `core-ktx`).
    implementation(libs.androidx.core.ktx)

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
    // `FollowedTripRepositoryImplTest` runs the real `FollowedTripDataSource` against a
    // temp-file DataStore (`PreferenceDataStoreFactory`), so the preferences artifact is needed
    // on the test classpath — `:core:datastore` keeps it `implementation`-scoped.
    testImplementation(libs.androidx.datastore.preferences)
    // In-memory Room + Robolectric — same pairing the DAO tests use. Lets
    // `FavouritesRepositoryImplTest` exercise the real DAO instead of mocking it.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
