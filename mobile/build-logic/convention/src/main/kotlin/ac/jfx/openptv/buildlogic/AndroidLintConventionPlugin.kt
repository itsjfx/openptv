package ac.jfx.openptv.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

// `openptv.android.lint` — placeholder. The actual `:lint` module with custom
// lint checks (ForbidAndroidLog etc.) lands in Phase 11 and detekt wiring
// lands in #13. For now the plugin just applies Android Lint as a standalone
// project plugin so we have a stable id to consume later without churning
// every module's build script.
class AndroidLintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.lint")
        }
    }
}
