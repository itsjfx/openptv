package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// `openptv.android.application` — what every `:app`-shaped module applies.
// Sets compileSdk / minSdk / targetSdk / JVM target. Does NOT set
// `applicationId` / `versionCode` / `versionName` / `buildTypes`; those are
// app-specific and belong in `:app/build.gradle.kts`.
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("openptv.spotless")

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = TARGET_SDK
            }
        }
    }
}
