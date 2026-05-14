// `:core:common` — cross-cutting types (`Result`, `Logger`, `RelativeTimeFormatter`).
// Needs an Android dep because `AndroidLogger` calls `android.util.Log` directly —
// and per docs/mobile/00-conventions.md this is the ONLY module allowed to do that.
// The detekt rule that enforces it lands in #13.
//
// Hilt is applied so the `LoggerModule` `@Provides` here is reachable from
// every consumer's `SingletonComponent` graph without each one having to
// re-bind it.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.common"
}

dependencies {
    // `LocationProvider` exposes `Coordinates` (from `:core:model`) in its public API, and
    // `RelativeTimeFormatter` exposes `kotlinx.datetime.Instant` / `Clock` — both deps are `api`
    // so consumers don't have to re-declare them.
    api(project(":core:model"))
    api(libs.kotlinx.datetime)

    // `LocationProvider.observe()` returns a `Flow<Coordinates>` — Flow lives in
    // `kotlinx-coroutines-core`, which `kotlinx-coroutines-android` already brings transitively.
    // Add it as `api` so callers can collect the flow without an extra dep declaration.
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
