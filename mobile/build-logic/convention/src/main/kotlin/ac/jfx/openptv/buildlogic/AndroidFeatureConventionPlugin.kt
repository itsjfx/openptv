package ac.jfx.openptv.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

// `openptv.android.feature` — meta-plugin for `:feature:*` modules. Applies
// `openptv.android.library` + `openptv.android.library.compose` +
// `openptv.android.hilt` and wires the four core modules every feature depends
// on: `:core:ui`, `:core:designsystem`, `:core:common`, `:core:navigation`.
//
// Those modules don't exist yet in this PR (multi-module split lands in #11),
// so each `project(":core:...")` lookup is guarded by `findProject`. Applying
// the plugin today with no feature modules existing is a no-op on the
// dependency side; once the modules land, the next time a feature applies this
// plugin it will pick them up automatically.
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("openptv.android.library")
            pluginManager.apply("openptv.android.library.compose")
            pluginManager.apply("openptv.android.hilt")

            // `androidx-hilt-navigation-compose` is always needed when a feature
            // hosts a Compose-bound Hilt ViewModel.
            dependencies {
                add(
                    "implementation",
                    libs.findLibrary("androidx-hilt-navigation-compose").get(),
                )

                listOf(
                    ":core:ui",
                    ":core:designsystem",
                    ":core:common",
                    ":core:navigation",
                ).forEach { path ->
                    findProject(path)?.let { add("implementation", project(path)) }
                }
            }
        }
    }
}
