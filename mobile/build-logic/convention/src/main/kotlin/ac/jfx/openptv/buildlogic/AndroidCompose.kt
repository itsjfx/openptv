package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

// Single source of truth for Compose dependencies. Library + application Compose
// convention plugins both delegate here so the Compose BOM is pinned exactly
// once. If we ever decide to drop tooling-preview/tooling, this is the only
// place to change.
internal fun Project.configureAndroidCompose(common: CommonExtension) {
    common.buildFeatures.compose = true

    val composeBom = libs.findLibrary("androidx-compose-bom").get()
    val composeUi = libs.findLibrary("androidx-compose-ui").get()
    val composeUiGraphics = libs.findLibrary("androidx-compose-ui-graphics").get()
    val composeUiToolingPreview = libs.findLibrary("androidx-compose-ui-tooling-preview").get()
    val composeUiTooling = libs.findLibrary("androidx-compose-ui-tooling").get()
    val composeMaterial3 = libs.findLibrary("androidx-compose-material3").get()

    dependencies {
        add("implementation", platform(composeBom))
        add("androidTestImplementation", platform(composeBom))
        add("implementation", composeUi)
        add("implementation", composeUiGraphics)
        add("implementation", composeUiToolingPreview)
        add("implementation", composeMaterial3)
        add("debugImplementation", composeUiTooling)
    }
}
