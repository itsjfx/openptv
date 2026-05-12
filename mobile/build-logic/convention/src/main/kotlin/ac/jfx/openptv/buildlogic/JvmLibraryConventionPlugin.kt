package ac.jfx.openptv.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

// `openptv.jvm.library` — pure-Kotlin/JVM modules: `:core:model`,
// `:core:common`, `:core:testing` (the parts with no Android deps), etc.
// Matches the JVM target and toolchain of the Android modules so consumers can
// share bytecode without compat warnings.
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            configureJvm()
        }
    }
}
