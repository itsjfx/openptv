// `:feature:search` — single screen ("Search stops") and its ViewModel.
// `openptv.android.feature` wires the four standard core deps via
// `findProject(...)?.let { add(...) }`, so we don't repeat them here — the
// plugin picks up `:core:common`, `:core:designsystem`, and `:core:navigation`
// the moment those projects exist in `settings.gradle.kts` (`:core:ui` and
// `:core:domain` are deferred; the plugin is a no-op on missing modules).
//
// Per the conventions doc, feature modules never depend on `:core:network` or
// `:core:database`. The repository interface comes from `:core:data`.
plugins {
    id("openptv.android.feature")
}

android {
    namespace = "ac.jfx.openptv.feature.search"
}

dependencies {
    // Repository interface only — never the network impl.
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Retrofit is an internal detail of `:core:network` — features never see
    // it. Except `HttpException` is the type the ViewModel pattern-matches on
    // when mapping errors to user-facing strings. That's an unfortunate leak
    // we'll fix in a follow-up by introducing a `DomainError` sealed type in
    // `:core:common` and translating at the data-layer boundary.
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:data-test"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
