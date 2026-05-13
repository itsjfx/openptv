package ac.jfx.openptv.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// `openptv.spotless` — applies `com.diffplug.spotless` and configures the same
// ktlint format everywhere. Re-applied by every Android / JVM convention plugin
// so individual modules don't have to opt in, matching NIA's pattern.
//
// When applied to the root project, also formats `build-logic/convention`'s
// Kotlin sources and the `*.gradle.kts` files at the root + under `build-logic`.
// NIA splits this into a separate `configureSpotlessForRootProject` function;
// we do it inline because we don't need the per-target divergence (no XML
// header to attach, no Apache header anywhere).
//
// ktlint rule disables (composable / test naming, property-naming, etc.) live
// in the top-level `.editorconfig` so the IDE and CLI see the same rule set.
// Matches NIA.
class SpotlessConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")

            val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
            val isRoot = target == rootProject

            extensions.configure<SpotlessExtension> {
                kotlin {
                    if (isRoot) {
                        target("build-logic/convention/src/**/*.kt")
                    } else {
                        target("src/**/*.kt")
                        targetExclude("**/build/**", "**/generated/**")
                    }
                    ktlint(ktlintVersion)
                    endWithNewline()
                }
                kotlinGradle {
                    if (isRoot) {
                        target(
                            "*.gradle.kts",
                            "build-logic/*.gradle.kts",
                            "build-logic/convention/*.gradle.kts",
                        )
                    } else {
                        target("*.gradle.kts")
                    }
                    ktlint(ktlintVersion)
                    endWithNewline()
                }
            }
        }
    }
}
