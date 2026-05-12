package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

// `openptv.android.library.roborazzi` — opt-in screenshot-test wiring. Applied
// alongside `openptv.android.library.compose` on modules that own a screenshot
// suite (currently `:core:designsystem`; per-feature smoke suites land later).
//
// Why opt-in and not transitively applied by the compose convention plugin:
// Roborazzi pulls Robolectric, which inflates the test classpath noticeably
// and is overkill for modules that only host UI tests on an emulator. Keeping
// it explicit means new modules get a 1-line opt-in decision.
//
// Defaults this plugin sets:
//   - `io.github.takahirom.roborazzi` Gradle plugin is applied so the
//     `recordRoborazziDebug` / `verifyRoborazziDebug` / `compareRoborazziDebug`
//     tasks exist.
//   - `testOptions.unitTests.isIncludeAndroidResources = true` (Robolectric
//     can't load merged resources / `R.string.*` without this).
//   - The standard test-side deps: roborazzi, roborazzi-compose,
//     roborazzi-junit-rule, robolectric, compose `ui-test-junit4` and
//     `ui-test-manifest`.
//
// Snapshots default to Roborazzi's own convention (under `src/test/snapshots/`
// when the test class lives in `src/test/`). We don't override
// `roborazzi.outputDir` — keeping defaults means new contributors don't have to
// learn a custom path.
class AndroidRoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")

            extensions.configure<LibraryExtension> {
                // Robolectric requires merged Android resources on the unit
                // test classpath, otherwise theme attributes / styles fail to
                // resolve and Compose's CompositionLocal lookups throw.
                testOptions.unitTests.isIncludeAndroidResources = true
            }

            val libsCatalog = libs
            dependencies {
                add(
                    "testImplementation",
                    libsCatalog.findLibrary("roborazzi").get(),
                )
                add(
                    "testImplementation",
                    libsCatalog.findLibrary("roborazzi-compose").get(),
                )
                add(
                    "testImplementation",
                    libsCatalog.findLibrary("roborazzi-junit-rule").get(),
                )
                add(
                    "testImplementation",
                    libsCatalog.findLibrary("robolectric").get(),
                )
                // Compose UI test deps go through the Compose BOM (already
                // pinned by `openptv.android.library.compose`, which is the
                // sibling plugin every Roborazzi-using module also applies).
                add(
                    "testImplementation",
                    libsCatalog.findLibrary("androidx-compose-ui-test-junit4").get(),
                )
                add(
                    "testImplementation",
                    libsCatalog.findLibrary("androidx-compose-ui-test-manifest").get(),
                )
            }
        }
    }
}
