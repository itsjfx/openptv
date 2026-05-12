package ac.jfx.openptv.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// `openptv.spotless` — applies `com.diffplug.spotless` to every Kotlin module
// and configures the same ktlint + license-header formats everywhere. Each
// Android / JVM convention plugin re-applies this one so individual modules
// don't have to opt in, matching NIA's pattern.
//
// Targets:
//   - `**/*.kt`        : ktlint + Apache-2.0 header.
//   - `**/*.kts`       : ktlint only. Build scripts don't get a header so the
//                        top-of-file comment that explains what each module is
//                        stays visible.
//
// Editorconfig overrides are intentionally absent — ktlint defaults until a
// real rule starts biting.
// Two ktlint rules clash with idiomatic Compose / Android code in this repo:
//
//   - `standard:function-naming` flags `@Composable fun App(...)`. Composables
//     are PascalCase by convention; the Compose lint catches the inverse case.
//   - `standard:property-naming` flags `internal const val TestTagFoo` style
//     constants used by Compose UI tests. Android / Compose-land already
//     standardised on PascalCase here.
//
// Both are well-known false positives. Mirrors what NIA's `.editorconfig`
// already disables. If anyone wants stricter checking later, drop these
// overrides and run `spotlessApply`.
private val ktlintOverrides: Map<String, String> = mapOf(
    "ktlint_standard_function-naming" to "disabled",
    "ktlint_standard_property-naming" to "disabled",
)

class SpotlessConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")

            val licenseHeaderFile = rootProject.file("spotless/license-header.kt")

            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("src/**/*.kt")
                    targetExclude("**/build/**", "**/generated/**")
                    ktlint().editorConfigOverride(ktlintOverrides)
                    licenseHeaderFile(licenseHeaderFile)
                }
                kotlinGradle {
                    target("*.gradle.kts", "src/**/*.gradle.kts")
                    targetExclude("**/build/**")
                    ktlint().editorConfigOverride(ktlintOverrides)
                }
            }
        }
    }
}
