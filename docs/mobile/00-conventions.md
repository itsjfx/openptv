# Mobile conventions

Shared rules every mobile phase assumes. Read once; phase docs reference this rather than restating.

## Platform targets

- **The app MUST run with zero Google Play services.** No GMS, Firebase, FCM, Play Billing, Maps SDK, ML Kit, or anything else that links `com.google.android.gms.*` or requires the Play Store. Any dependency that hard-depends on GMS is disqualified — find an AOSP-friendly alternative or build it.
- **Canonical deployment target: GrapheneOS.** Decisions that trade convenience for de-Googled compatibility default to the GrapheneOS-friendly option (e.g. push delivery via UnifiedPush rather than FCM; maps via MapLibre + OSM rather than Google Maps; location via `LocationManager` rather than Fused Location Provider).
- **Local test target: AOSP system image** (`system-images;android-XX;default;x86_64`), not `google_apis*`. The AOSP image has no Play Services, so anything that silently relies on GMS will fail here exactly as it would on GrapheneOS. CI emulator jobs use the same `default` image variant.
- **Network freedom**: nothing should require a Google account, push token, or attestation handshake to function.

## Project conventions

- **Gradle**: Kotlin DSL, version catalog (`gradle/libs.versions.toml`), convention plugins under `build-logic/` (NIA-style). No `buildSrc`.
- **Kotlin**: 2.x, JVM target 11, JDK 21 toolchain.
- **Compose BOM**: every Compose dep imports through `platform(libs.androidx.compose.bom)` — set up once in `openptv.android.library.compose` / `openptv.android.application.compose`.
- **KSP** for all annotation processing (Hilt, Room, kotlinx.serialization). No KAPT.
- **R8**: full mode in release builds.
- **Lint / format**:
  - **Spotless** (ktlint formatter) wired into every module via `openptv.spotless`. Apache-2.0
    license header on every `.kt` source; `.gradle.kts` build scripts get ktlint-only so their
    top-of-file explainer comments stay visible. Two ktlint rules are disabled in
    `SpotlessConventionPlugin` (`standard:function-naming` so PascalCase composables are accepted,
    `standard:property-naming` so `internal const val TestTagFoo` constants used by Compose UI
    tests pass). Run `./gradlew spotlessApply` to fix, `./gradlew spotlessCheck` to verify.
  - **detekt** for static analysis (complexity, magic numbers, naming).
  - **Dependency Guard** baselines transitive deps for `:app` (catches accidental dep churn).

<a id="pre-commit"></a>

### Pre-commit hook (recommended)

Drop the following into `.git/hooks/pre-commit` and `chmod +x` it. The hook runs the formatter
against the staged tree before each commit so style drift never makes it onto a branch. It is a
**recommendation, not enforcement** — the canonical gate is `spotlessCheck` in CI.

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)/mobile"
./gradlew --quiet spotlessApply
git add -u
```
- **Logging**: project-internal `Logger` interface in `:core:common`; `Log.d` / `println` outside `:core:common` is a detekt failure.
- **Strings**: `:core:designsystem` owns shared strings; feature strings in feature modules.
- **Resources**: every module has a unique resource prefix (`feature_search_*`, `core_designsystem_*`) — enforced by `android.resourcePrefix` set in the convention plugin from the module path.
- **Time**: `kotlinx.datetime.Instant` and `kotlinx.datetime.LocalDateTime`. Avoid `java.util.Date`.

## Module conventions

Module IDs map 1-to-1 to directory paths:

```
:app                              mobile/app
:benchmarks                       mobile/benchmarks                (Phase 11)
:core:common                      mobile/core/common
:core:data                        mobile/core/data                 (repository interfaces + impls)
:core:data-test                   mobile/core/data-test            (fakes; @TestInstallIn modules)
:core:database                    mobile/core/database
:core:datastore                   mobile/core/datastore            (typed Preference DSL)
:core:designsystem                mobile/core/designsystem
:core:domain                      mobile/core/domain               (use cases only)
:core:model                       mobile/core/model                (pure data classes, no Android deps)
:core:navigation                  mobile/core/navigation           (NavKey / route definitions)
:core:network                     mobile/core/network
:core:notifications               mobile/core/notifications        (Phase 8)
:core:testing                     mobile/core/testing              (object mothers, fake clocks)
:core:ui                          mobile/core/ui
:feature:<name>                   mobile/feature/<name>
:lint                             mobile/lint                      (custom lint rules; Phase 11)
:sync:work                        mobile/sync/work                 (Phase 7)
:sync:sync-test                   mobile/sync/sync-test            (NeverSyncing fakes)
:ui-test-hilt-manifest            mobile/ui-test-hilt-manifest     (Hilt-aware Activity for Compose UI tests)
```

A feature module:
- depends on `:core:domain`, `:core:designsystem`, `:core:ui`, `:core:common`, `:core:navigation`
- never depends on another `:feature:*` (cross-feature navigation goes through `:app` and `:core:navigation`)
- never depends on `:core:network`, `:core:database` directly — only through repository interfaces in `:core:data` and use cases in `:core:domain`
- consumes fakes from `:core:data-test` in tests via `@TestInstallIn`

## Architecture rules

- **Repository interface and implementation both live in `:core:data`** (matches NIA). Domain types in `:core:model`. Network DTOs internal to `:core:network`, never exposed.
- **Use cases** (only when they orchestrate ≥2 repos or non-trivial logic) live in `:core:domain` as `*UseCase.kt` classes with a single `operator fun invoke(...)`.
- **Errors**: repositories return `Result<T>` (sealed: `Success<T>`, `Error(Throwable)`, `Loading`) — same shape as NIA's `core/common/.../result/Result.kt`. ViewModels map `Result<T>` to a screen-specific `UiState`.
- **ViewModel state**: a single sealed `UiState` per screen, exposed as `StateFlow<UiState>`. Events are method calls on the ViewModel, not channels.
- **Composables**: stateless ones receive `state: T` and `onEvent: (Event) -> Unit`. A thin stateful wrapper hoists the ViewModel.
- **Settings**: typed `Preference` sealed classes in `:core:datastore`, each exposed as a `compositionLocalOf` so Compose code reads `LocalThemeMode.current` rather than injecting a settings repo.
- **Navigation**: `NavKey` definitions (Navigation 3) in `:core:navigation`. Type-safe routes; no string magic.

## Testing rules

- **Test framework**: JUnit 4 (`junit:junit:4.13.2`). Compose UI rules and `androidx.test.ext.junit` are JUnit-4-only.
- **Assertions**: Truth (`com.google.truth:truth`) for new tests. Don't mix with Hamcrest or AssertJ.
- **Test doubles, in priority order:**
  1. **Real objects** for pure types (formatters, mappers, builders).
  2. **Object Mothers** in `:core:testing` — `Stops.aStop()`, `Departures.aDeparture()`, with sensible defaults overridable per call.
  3. **Hand-written fakes** in `:core:data-test` — fake repositories backed by an in-memory list, exposed through `@TestInstallIn` modules so feature tests inherit them automatically.
  4. **MockK** as a last resort — only when the real seam is opaque or you genuinely need behaviour verification. If a fake already exists, prefer it.
- **Coroutines**: `kotlinx-coroutines-test` with `StandardTestDispatcher` for ViewModel tests (manual advance), `UnconfinedTestDispatcher` for repositories.
- **Flow**: Turbine.
- **HTTP**: OkHttp `MockWebServer`. Never mock `OkHttpClient`.
- **DB**: Room in-memory (`Room.inMemoryDatabaseBuilder`).
- **Compose UI**: `createComposeRule` for module-local screens; `createAndroidComposeRule<HiltComponentActivity>()` (from `:ui-test-hilt-manifest`) for Hilt-injected screens.
- **Screenshot**: Roborazzi on the JVM. Locked devices: `phone`, `phone_landscape`, `tablet`. Run on `:core:designsystem` + per-feature smoke screen.
- **Hilt tests**: `@HiltAndroidTest`, `HiltAndroidRule`. Production fakes from `:core:data-test`; one-off test bindings via `@TestInstallIn` in the test source set.
- **Object Mother template**:
  ```kotlin
  object Stops {
      fun aStop(
          id: StopId = StopId(1071),
          name: String = "Flinders Street Railway Station",
          // ...
      ) = Stop(id, name, /* ... */)
  }
  ```
  Default to the same stable Flinders Street fixture across the codebase so failures are visually consistent.
- **Coverage gates** (CI): line coverage ≥80% on `:core:*`; ≥85% on `:feature:*` ViewModels; UI tests cover the golden path per feature.

## CI

- GitHub Actions workflow `.github/workflows/mobile-ci.yml`, triggered on PRs / `master` pushes
  touching `mobile/**` or the workflow file itself.
- Parallel jobs: `lint-spotless-detekt` (`:app:lintDebug spotlessCheck detekt`), `unit-test`
  (`test`, uploads `mobile/**/build/reports/tests/` on failure), `screenshot`
  (`verifyRoborazziDebug` — stubbed as `--dry-run … || true` until Roborazzi lands in #16),
  `dependency-guard` (`:app:dependencyGuard`), and `assemble-debug` (`:app:assembleDebug`,
  uploads the debug APK as artifact `app-debug-<sha>` for 14 days).
- JDK 21 (`temurin`) via `actions/setup-java`; Gradle caching via `gradle/actions/setup-gradle@v4`
  (defaults). `concurrency: cancel-in-progress` so superseded runs on the same PR get cancelled.
- Release builds (tag-triggered) sign with a key from GitHub Actions secrets (`KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), upload APK to GitHub Releases — Phase 11.

## Style

- Hilt: prefer constructor injection. Modules only for things you can't construct (interfaces, library-provided instances).
- No `LiveData`. Use `StateFlow` everywhere.
- No `runBlocking` in production code.
- No Timber — use the `Logger` interface in `:core:common`. NIA also avoids Timber.
