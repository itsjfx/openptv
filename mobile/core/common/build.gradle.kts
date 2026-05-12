// `:core:common` — cross-cutting types (`Result`, `Logger`). Needs an Android
// dep because `AndroidLogger` calls `android.util.Log` directly — and per
// docs/mobile/00-conventions.md this is the ONLY module allowed to do that.
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
    // `javax.inject.Inject` / `Singleton`. Hilt brings these transitively via
    // `hilt-android`, but pinning the explicit dep keeps the API surface
    // visible.
}
