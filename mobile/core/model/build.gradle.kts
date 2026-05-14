// `:core:model` — pure-Kotlin domain types (`Stop`, `StopId`, `RouteType`,
// `AppSettings`, `Departure`, `StopDetail`). No Android deps, so we use the JVM
// library convention plugin instead of `openptv.android.library`. That keeps the
// consumer classpath honest: anyone importing a model type can't accidentally drag
// in `android.*`.
plugins {
    id("openptv.jvm.library")
}

dependencies {
    // `Departure` carries `kotlinx.datetime.Instant` fields — the type is part of the
    // public API so consumers shouldn't have to re-declare the dep. `api` (not
    // `implementation`) so an import of `Instant` resolves transitively wherever
    // `:core:model` is on the classpath.
    api(libs.kotlinx.datetime)

    // `Coordinates.distanceTo` (haversine, issue #37) has a tiny JUnit + Truth test so
    // the formula isn't smoke-only. Test deps mirror `:core:common`'s shape.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
