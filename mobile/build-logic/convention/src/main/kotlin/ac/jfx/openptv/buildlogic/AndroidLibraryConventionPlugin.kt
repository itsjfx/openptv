package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// `openptv.android.library` — applied by every `:core:*` / `:feature:*` /
// `:sync:*` Android library module. In addition to the shared compileSdk /
// minSdk / JVM config, derives a `resourcePrefix` from the Gradle module path
// so cross-module resource collisions are impossible at compile time.
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("openptv.android.lint")
            pluginManager.apply("openptv.spotless")

            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                // Library modules don't have a `targetSdk`; AGP 9 dropped it
                // from the library DSL. Only set it on application modules.
                resourcePrefix = resourcePrefixFromPath()
            }
        }
    }
}
