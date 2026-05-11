# Mobile Phase 1 — Project skeleton, theme, navigation

> Goal: a runnable Compose app with multi-module Gradle, Hilt, Material You theme, Navigation 3, and a CI workflow. No business features yet.

**Depends on:** none.
**Blocks:** every other mobile phase.

## Scope

Bootstrap the Android project so subsequent phases can drop in features without touching build infrastructure. Only one screen exists: a placeholder Home with a theme toggle.

## Deliverables

- [ ] `mobile/` Gradle project with Kotlin DSL + version catalog.
- [ ] Convention plugins in `mobile/build-logic/` (NIA-style set):
  - [ ] `openptv.android.application`
  - [ ] `openptv.android.application.compose`
  - [ ] `openptv.android.library`
  - [ ] `openptv.android.library.compose`
  - [ ] `openptv.android.feature`
  - [ ] `openptv.android.hilt`
  - [ ] `openptv.android.lint`
  - [ ] `openptv.jvm.library`
  - [ ] `openptv.spotless` (ktlint formatting + license headers)
  - [ ] Compose BOM pin set inside the `*.compose` plugins via `platform(libs.androidx.compose.bom)`
- [ ] Modules created and wired:
  - [ ] `:app`
  - [ ] `:core:common`
  - [ ] `:core:designsystem`
  - [ ] `:core:model`              (empty for now; phase 2 populates)
  - [ ] `:core:navigation`         (top-level NavKey definitions)
  - [ ] `:core:ui`
  - [ ] `:core:testing`
  - [ ] `:ui-test-hilt-manifest`   (skeleton with `HiltComponentActivity` so feature androidTests can use `createAndroidComposeRule<HiltComponentActivity>()` from Phase 2 onwards)
- [ ] `:app/src/main/AndroidManifest.xml` declares `OpenPtvApplication` (`@HiltAndroidApp`).
- [ ] Single Compose `MainActivity` host with `OpenPtvTheme { App() }`.
- [ ] Material 3 theme:
  - [ ] Dynamic colour on Android 12+ (`dynamicLightColorScheme` / `dynamicDarkColorScheme`)
  - [ ] Hand-tuned fallback palette in `:core:designsystem` for Android 8–11 — port ReadYou's `ui/theme/palette/MaterialYouStandard.kt` and `TonalPalettes.kt` outright (Apache 2.0 → Apache 2.0 compatible)
  - [ ] `MotionScheme` opt-in (`expressive()`), per ReadYou `ui/theme/Theme.kt`
  - [ ] System / light / dark mode setting (persisted via DataStore — typed `Preference` DSL added in Phase 4; fine to use `remember` for now)
- [ ] Navigation 3 graph with one destination (Home placeholder).
- [ ] `OpenPtvLogger` interface in `:core:common` with an Android `Log.*` impl in `:app`.
- [ ] Spotless config + license headers committed; `./gradlew spotlessCheck` clean.
- [ ] detekt config (`mobile/detekt.yml`) committed; CI fails on detekt errors.
- [ ] Dependency Guard plugin applied to `:app` with an initial baseline (`./gradlew :app:dependencyGuardBaseline`).
- [ ] GitHub Actions `mobile-ci.yml`: `spotlessCheck`, `detekt`, `lintDebug`, `test`, `dependencyGuard`, `assembleDebug`.

## Out of scope

- Networking. (Phase 2.)
- DataStore-backed settings. (Phase 2 or 4.)
- Search, favourites, map. (Phases 2–5.)

## Acceptance criteria

- `./gradlew :app:assembleDebug` succeeds on a clean checkout.
- App installs on an Android 8.0 emulator and launches without crash.
- Home screen renders Material 3 theme with correct dynamic colour on Android 12+ device.
- Theme switcher (light/dark/system) cycles correctly.
- `./gradlew test` runs at least one passing JUnit 4 test in each module.
- `./gradlew spotlessCheck detekt` are both green on first commit.
- `./gradlew :app:dependencyGuard` is green (baseline matches lockfile).
- CI workflow runs all of the above on push.

## Test plan

- `:core:designsystem`
  - Roborazzi: `OpenPtvTheme` light, dark, and dynamic-colour previews of the home placeholder.
  - JVM: `ThemeKtTest` asserts colour scheme defaults for each mode.
- `:core:common`
  - `OpenPtvLoggerTest` (no-op test impl exposed in `:core:testing`).
- `:app`
  - `MainActivityTest` (Hilt + `createAndroidComposeRule`) launches and asserts the Home placeholder is composed.

## Implementation notes for a Spring developer

- Gradle convention plugins are the Android answer to a Spring parent BOM — they let every module say "I am an Android library" in one line and inherit the Compose / Kotlin / detekt setup.
- `@HiltAndroidApp` on the `Application` is equivalent to `@SpringBootApplication` — it kicks off the DI container.
- A `ViewModel` is a request-scoped service that survives configuration change (rotation). Hilt's `hiltViewModel()` is the lookup.
- Material You "dynamic colour" reads the user's wallpaper on Android 12+ and emits a Material 3 `ColorScheme` from it. Below that we ship a fixed palette.

## References

- [Android architecture guide](https://developer.android.com/topic/architecture)
- [Now in Android `build-logic/`](https://github.com/android/nowinandroid/tree/main/build-logic) — direct precedent for every convention plugin in this phase
- ReadYou `ui/theme/` — palette + motion scheme references
- [Hilt setup](https://developer.android.com/training/dependency-injection/hilt-android)
- [Dependency Guard](https://github.com/dropbox/dependency-guard)
