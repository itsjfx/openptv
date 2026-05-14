// `:core:database` — Room persistence layer (Phase 4). Owns the
// [OpenPtvDatabase] singleton, every `@Entity`, every `@Dao`, and the Hilt
// `DatabaseModule` that hands the DB / DAOs to the rest of the app.
//
// Allowed dependencies (per docs/mobile/00-conventions.md):
//   :core:model — entities cache enum fields (`RouteType`) declared there.
// Never depends on `:core:data` / `:core:network` / any `:feature:*` module —
// data-layer types compose this module, not the other way around.
//
// No dedicated `openptv.android.room` convention plugin yet: this is the only
// module that needs Room, so the wiring is inlined here. If a second consumer
// ever appears, extract a `RoomConventionPlugin` then.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.database"

    defaultConfig {
        // Hosts the on-device migration tests; the runner is the same one
        // `:app` uses for its instrumented suite.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Room writes the schema JSON during compileDebugKotlin via KSP. The
    // argument key `room.schemaLocation` is what Room's KSP processor reads;
    // pointing it at a checked-in directory means the v1 schema lives in
    // git (acceptance criterion: schema diff visible in PR). The directory
    // matches the @Database class's FQN (`ac.jfx.openptv.OpenPtvDatabase`) —
    // Room creates `<dir>/<fqn>/<version>.json`.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        // Room 2.6+ supports incremental schema generation. Keep it off for
        // reproducibility: incremental can miss column-rename diffs that the
        // full pass catches.
        arg("room.incremental", "false")
    }

    // Source the schema directory as an androidTest asset so future
    // `MigrationTestHelper`-based tests can open the v1 JSON. The helper
    // looks for schemas under the test APK's assets at runtime.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    // Robolectric needs `includeAndroidResources = true` so the runtime can
    // find the manifest + resources when constructing the test `Application`.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:model"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Room ships an in-memory builder via `Room.inMemoryDatabaseBuilder`; the
    // Robolectric runner gives JVM tests a Context without booting an
    // emulator. AGP 9 + Robolectric 4.x are the standard pairing.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // Migration test harness — runs on emulator/device. Empty migration set
    // for v1 but the helper instantiates so the framework is ready for v2.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
}
