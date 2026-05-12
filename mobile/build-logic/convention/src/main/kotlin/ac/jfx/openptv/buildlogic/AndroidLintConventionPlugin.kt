package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// `openptv.android.lint` — applies Android Lint with shared defaults. Dispatch
// matches NIA: if the module is already an Android application or library,
// configure that extension's `lint { }` block; otherwise apply the standalone
// `com.android.lint` plugin. Applying `com.android.lint` on top of an existing
// Android module is invalid and will fail the build, hence the `when {}`.
//
// `:core:*` / `:feature:*` / `:app` get this plugin transitively via
// `openptv.android.library` / `openptv.android.application`; pure-JVM modules
// (`openptv.jvm.library`) hit the `else` branch and run lint standalone.
//
// The custom lint checks (ForbidAndroidLog etc.) and detekt wiring land in
// later phases; this plugin only owns the shared report config.
class AndroidLintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            when {
                pluginManager.hasPlugin("com.android.application") ->
                    extensions.configure<ApplicationExtension> { lint(Lint::configure) }

                pluginManager.hasPlugin("com.android.library") ->
                    extensions.configure<LibraryExtension> { lint(Lint::configure) }

                else -> {
                    pluginManager.apply("com.android.lint")
                    extensions.configure<Lint>(Lint::configure)
                }
            }
        }
    }
}

private fun Lint.configure() {
    xmlReport = true
    sarifReport = true
    checkDependencies = true
    disable += "GradleDependency"
}
