// Registers OpenPTV's convention plugins. Apply with `id("openptv.android.library")`
// etc. from a module's `build.gradle.kts`. The composite build means we don't
// publish anything; the plugins are resolved straight out of this subproject's
// classpath.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "ac.jfx.openptv.buildlogic"

// Compile the plugin sources against JVM 11 to match every module they configure.
// JDK 21 toolchain is set on the root build so the daemon already runs on 21.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // We don't want to leak the plugin marker artifacts into the consumer's
    // classpath, so depend on the plugin classes directly. `libs.plugins.X` maps
    // to a Provider<PluginDependency> — convert to the implementation module via
    // the `pluginId` -> Gradle plugin marker pattern NIA uses.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    // Spotless's Gradle plugin powers `openptv.spotless`. Pinned to 7.x in the
    // catalog so the convention plugin classpath (JVM 11) doesn't trip over
    // Spotless 8's JDK 17 bytecode requirement.
    compileOnly(libs.spotless.gradle.plugin)
    // detekt's Gradle plugin powers `openptv.detekt`. Same `compileOnly`
    // pattern as the other tooling plugins above — the consumer build picks
    // it up via the corresponding `apply false` declaration in the root
    // `mobile/build.gradle.kts`.
    compileOnly(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "openptv.android.application"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "openptv.android.application.compose"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "openptv.android.library"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "openptv.android.library.compose"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "openptv.android.feature"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "openptv.android.hilt"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidLint") {
            id = "openptv.android.lint"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidLintConventionPlugin"
        }
        register("jvmLibrary") {
            id = "openptv.jvm.library"
            implementationClass = "ac.jfx.openptv.buildlogic.JvmLibraryConventionPlugin"
        }
        register("spotless") {
            id = "openptv.spotless"
            implementationClass = "ac.jfx.openptv.buildlogic.SpotlessConventionPlugin"
        }
        register("detekt") {
            id = "openptv.detekt"
            implementationClass = "ac.jfx.openptv.buildlogic.DetektConventionPlugin"
        }
        register("androidLibraryRoborazzi") {
            id = "openptv.android.library.roborazzi"
            implementationClass = "ac.jfx.openptv.buildlogic.AndroidRoborazziConventionPlugin"
        }
    }
}
