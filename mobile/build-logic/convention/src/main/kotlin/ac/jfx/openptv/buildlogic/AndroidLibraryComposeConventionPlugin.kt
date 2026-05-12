package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// `openptv.android.library.compose` — applied alongside
// `openptv.android.library` for `:core:designsystem`, `:core:ui`, every
// `:feature:*`. Pins the Compose BOM and pulls in the always-needed Compose UI
// libraries.
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<LibraryExtension> {
                configureAndroidCompose(this)
            }
        }
    }
}
