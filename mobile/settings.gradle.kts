pluginManagement {
    // The composite build under `build-logic/` publishes `openptv.*` plugins.
    // It has to be included before `repositories { ... }` for Gradle to resolve
    // their ids from `plugins { id("openptv.android.library") }` blocks.
    includeBuild("build-logic")

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
}

rootProject.name = "openptv"

include(":app")
