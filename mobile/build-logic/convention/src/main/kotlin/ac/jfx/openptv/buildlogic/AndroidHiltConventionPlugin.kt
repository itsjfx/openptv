package ac.jfx.openptv.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

// `openptv.android.hilt` — applies Hilt + KSP and wires up the standard Hilt
// runtime + compiler deps. KSP, never KAPT: kapt forces a full kotlinc pass
// per round and is roughly 2x slower than KSP on Hilt-heavy modules.
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
