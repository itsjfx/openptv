package ac.jfx.openptv.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

// `openptv.detekt` — applies `io.gitlab.arturbosch.detekt` to every Kotlin
// module and points it at the shared `mobile/detekt.yml`. Re-applied by every
// android / jvm convention plugin so individual modules don't have to opt in,
// matching the same transitive pattern as `openptv.spotless`.
//
// Notes on the rule set:
//   - `formatting` (the ktlint-backed ruleset) is intentionally NOT wired in.
//     Spotless owns formatting; detekt owns structure. Mixing both means two
//     tools fight over the same fixes.
//   - The `:lint:detekt` module supplies the project-specific rules (currently
//     `ForbidAndroidLog`). Added via `detektPlugins(...)` so they are
//     discovered through detekt's `RuleSetProvider` SPI without any per-module
//     wiring.
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

            extensions.configure<DetektExtension> {
                config.setFrom(rootProject.file("detekt.yml"))
                buildUponDefaultConfig = true
                allRules = false
                // Each module produces its own report; `./gradlew detekt`
                // aggregates them via task-name matching. No baselines: the
                // policy in the issue is to fix over baseline, and a tree-wide
                // baseline would dilute that.
                autoCorrect = false
                parallel = true
            }

            // `:lint:detekt` itself can't depend on itself — guard so the
            // module that supplies the rules just runs the built-in checks.
            if (path != ":lint:detekt") {
                dependencies {
                    add("detektPlugins", project(":lint:detekt"))
                }
            }

            tasks.withType<Detekt>().configureEach {
                // JVM 11 source/target bytecode is consistent across the
                // project, so report against that. Avoids surprises if the
                // toolchain bumps the daemon's JDK independently.
                jvmTarget = JVM_TARGET.toString()
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                    sarif.required.set(false)
                    md.required.set(false)
                    txt.required.set(false)
                }
                // Skip generated sources and the convention plugin's own
                // outputs; both folders contain code that isn't ours to fix.
                exclude("**/build/**", "**/generated/**")
            }
        }
    }
}
