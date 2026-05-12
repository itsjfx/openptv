// Composite build for the OpenPTV convention plugins. Included from
// `mobile/settings.gradle.kts` via `includeBuild("build-logic")`. Mirrors the
// Now-in-Android pattern: a single `convention/` subproject that registers
// `Plugin<Project>` implementations under the `openptv.*` plugin namespace.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // Reuse the root project's version catalog so plugin code can read the same
    // versions the app uses (`libs.versions.toml`).
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
