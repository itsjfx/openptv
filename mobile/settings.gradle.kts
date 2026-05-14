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

// `:core:*` — shared libraries. Each one has a single responsibility per
// docs/mobile/00-conventions.md. `:core:domain` and `:core:ui` are intentionally
// absent from this list: per the issue, modules with no code to host don't get
// invented. They'll be added in the phase that introduces use cases / shared
// Compose primitives.
include(":core:common")
include(":core:data")
include(":core:data-test")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":core:domain")
include(":core:model")
include(":core:navigation")
include(":core:network")
include(":core:testing")

// `:feature:*` — single-screen features. Each one depends on `:core:domain`,
// `:core:designsystem`, `:core:ui`, `:core:common`, `:core:navigation` (the
// feature convention plugin guards those with `findProject` so missing ones are
// a no-op, not a build break).
include(":feature:search")
include(":feature:stop-detail")

// `:ui-test-hilt-manifest` — minimal Hilt-aware Activity that feature
// androidTests host themselves in for Compose UI tests. Borrowed verbatim from
// NIA.
include(":ui-test-hilt-manifest")

// `:lint:detekt` — project-specific detekt rules. JVM-only; consumed by the
// `openptv.detekt` convention plugin via `detektPlugins(project(...))`. Not
// the Android Lint framework — those custom checks would live under `:lint:android`
// and ship in Phase 11 per the architecture spec.
include(":lint:detekt")
