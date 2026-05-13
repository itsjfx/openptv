// `:core:domain` — use cases only, per `docs/mobile/00-conventions.md`. Android library
// (rather than `openptv.jvm.library`) because it depends on `:core:data` and `:core:common`,
// which are Android libraries themselves. Use cases here still avoid Android types in their
// own code — they orchestrate repositories and return `Result<T>` or `Flow<Result<T>>`. The
// AGP shell is a transitive consequence of the data-layer surface, not a license to import
// `Context` / `Uri` etc.
//
// Hilt is applied so `@Inject` on the use-case constructors resolves to Hilt's compile-time
// graph in feature modules.
//
// Allowed dependencies:
//   :core:data (interfaces only), :core:model, :core:common.
// Never `:core:network` or `:core:database` directly — those leak DTOs / DAOs across the
// repository boundary.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
}

android {
    namespace = "ac.jfx.openptv.core.domain"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    // Use cases hold a reference to repository interfaces — `:core:data` is on the public API
    // surface so consumers see `StopDetailRepository`, `DepartureRepository` via this module.
    api(project(":core:data"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(project(":core:testing"))
}
