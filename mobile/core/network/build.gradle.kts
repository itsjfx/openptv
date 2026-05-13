// `:core:network` — Retrofit/OkHttp graph plus the wire DTOs. Marked
// `internal` everywhere so DTOs never leak past this module's boundary; consumers
// only see the domain models from `:core:model`.
//
// Needs Hilt because [NetworkModule] is a `@Module` with `@Provides` functions.
plugins {
    id("openptv.android.library")
    id("openptv.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ac.jfx.openptv.core.network"

    // Retrofit DTOs read `BuildConfig.DEBUG` indirectly through the HTTP
    // logging interceptor; AGP generates BuildConfig.DEBUG automatically when
    // `buildConfig = true`. (NetworkModule references `BuildConfig.DEBUG` from
    // this module's namespace.)
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:model"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
