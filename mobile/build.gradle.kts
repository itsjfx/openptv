// Top-level build file for the OpenPTV mobile project.
// Plugins are declared with `apply false` so individual modules can opt in.
// Note: AGP 9+ ships Kotlin support built in, so the standalone
// `org.jetbrains.kotlin.android` plugin is no longer applied to Android modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
