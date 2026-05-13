// Top-level build file for the OpenPTV mobile project.
// Plugins are declared with `apply false` so individual modules can opt in.
// Note: AGP 9+ ships Kotlin support built in, so the standalone
// `org.jetbrains.kotlin.android` plugin is no longer applied to Android modules.
//
// `openptv.spotless` is applied directly here so the root `build.gradle.kts`
// (and any other `.kts` under the root project) gets formatted. Every other
// module picks it up transitively via its android / jvm convention plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // `apply false` puts Spotless's plugin jar on the root build's classpath
    // so the convention plugin can apply it by id and reference
    // `SpotlessExtension` at runtime. The root project still applies the
    // convention plugin below, which is what actually configures the formats.
    alias(libs.plugins.spotless) apply false
    // Same `apply false` pattern for detekt: pulls the plugin jar onto every
    // subproject's classpath so `openptv.detekt` can apply it by id. We don't
    // apply detekt at the root project level because there's no Kotlin source
    // here for it to analyse — only build scripts, which detekt doesn't lint.
    alias(libs.plugins.detekt) apply false
    // Dependency Guard — applied directly in `:app/build.gradle.kts` (only the
    // app rolls up the production APK classpath), but listed here `apply false`
    // so every subproject sees the same plugin jar on its classpath. Mirrors
    // NIA's root build script pattern of listing every used plugin with
    // `apply false` so transitive plugin-dependency versions can't drift.
    alias(libs.plugins.dependency.guard) apply false
    id("openptv.spotless")
}
