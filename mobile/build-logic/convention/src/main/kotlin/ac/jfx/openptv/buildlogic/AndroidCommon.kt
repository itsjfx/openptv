package ac.jfx.openptv.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Shared SDK + JVM configuration applied by every Android convention plugin.
// Keeping this in one place is the entire point of this composite build:
// bumping `compileSdk` or `minSdk` is a one-line change, not a ten-module
// find-and-replace.
internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26
internal const val TARGET_SDK = 36

internal val JVM_TARGET: JavaVersion = JavaVersion.VERSION_11
internal const val JDK_TOOLCHAIN = 21

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Configures `compileSdk`, `minSdk`, JVM source/target compat, and Java 11
// bytecode on the Kotlin compiler. Works for both `application` and `library`
// AGP extensions because they share the `CommonExtension` base. The DSL
// block-style helpers (`defaultConfig { }`, `compileOptions { }`) only exist
// on the concrete `ApplicationExtension` / `LibraryExtension` subtypes in
// AGP 9, so this method uses property-style access on the bare `CommonExtension`
// which works regardless of subtype.
internal fun Project.configureAndroidCommon(common: CommonExtension) {
    common.compileSdk = COMPILE_SDK
    common.defaultConfig.minSdk = MIN_SDK

    common.compileOptions.sourceCompatibility = JVM_TARGET
    common.compileOptions.targetCompatibility = JVM_TARGET

    common.packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    configureKotlinAndroid()
}

private fun Project.configureKotlinAndroid() {
    extensions.configure(KotlinAndroidProjectExtension::class.java) {
        jvmToolchain(JDK_TOOLCHAIN)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

// Pure-JVM modules don't have an Android extension — set source/target compat
// directly on the Java plugin and on the Kotlin JVM plugin.
internal fun Project.configureJvm() {
    extensions.configure(JavaPluginExtension::class.java) {
        sourceCompatibility = JVM_TARGET
        targetCompatibility = JVM_TARGET
    }
    extensions.configure(KotlinJvmProjectExtension::class.java) {
        jvmToolchain(JDK_TOOLCHAIN)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

// Derives an Android `resourcePrefix` from the Gradle project path so cross-
// module resource collisions stay impossible. `:core:designsystem` becomes
// `core_designsystem_`, `:feature:search` becomes `feature_search_`, etc.
internal fun Project.resourcePrefixFromPath(): String =
    path
        .removePrefix(":")
        .replace(':', '_')
        .replace('-', '_')
        .plus("_")
