> Full architecture spec: docs/architecture.md

# OpenPTV

OpenPTV is an open-source Android client plus a Go backend proxy for the PTV (Public Transport Victoria) Timetable API v3. The mobile app aims to be a small, fast, ad-free, Material You alternative to the official PTV app. The Go proxy signs requests upstream so the mobile client never holds the PTV signing key; auth and rate-limiting are handled at the edge, not in the application.

## Non-goals

- iOS, web, desktop. Android only. The Hilt DI choice trades portability for ergonomics.
- Account systems, cross-device sync, social features.

## Mobile architecture

Standard three-layer Android architecture per the official guide.

```
UI Layer        :app, :feature:*       Compose screens, ViewModels;
                                       StateFlow<UiState> mapped from Result<T>
Domain Layer    :core:domain           Use cases (thin, optional)
Data Layer      :core:data             Repository interfaces + impls (SSOT)
                :core:database         DAOs
                :core:datastore        Preferences (typed DSL)
                :core:network          Retrofit + DTOs (internal)
```

- **UI Layer** (`:app`, `:feature:*`): Compose screens and ViewModels. ViewModels expose `StateFlow<UiState>` and accept events as method calls.
- **Domain Layer** (`:core:domain`, thin and optional): use cases that orchestrate multiple repositories.
- **Data Layer** (`:core:data`, `:core:database`, `:core:datastore`, `:core:network`): repository interfaces plus implementations; DAOs; preferences; Retrofit + DTOs (internal).

### Key principles

- **Unidirectional state flow.** UI is a function of state. ViewModels emit `UiState`; UI dispatches events back as method calls.
- **Single source of truth** for each piece of data — usually the database for owned data, the network repository for ephemeral data such as departures.
- **`Result<T>`** is a sealed type with `Success<T>`, `Error(Throwable)`, and `Loading`. It flows from repository to ViewModel; ViewModels map it into a screen-specific `UiState`. Same shape as NIA's `core/common/.../result/Result.kt`.
- **Domain models live in `:core:model`** as pure data classes with no Android dependencies. Network DTOs stay internal to `:core:network` and never leak into domain or data interfaces.
- **Navigation routes live in `:core:navigation`** so feature modules can navigate to each other's destinations without depending on each other.
- Repository interface and implementation both live in `:core:data`. Use cases in `:core:domain` depend on the interface.

## Testing

- **Framework**: JUnit 4. Compose UI rules and `androidx.test.ext.junit` are JUnit-4-only.
- **Assertions**: Truth. Example: `assertThat(result).isInstanceOf(Result.Success::class.java)`.
- **Test doubles, in priority order**:
  1. **Real objects** for pure types (formatters, mappers) — construct directly.
  2. **Object Mothers** in `:core:testing` (e.g. `Stops.aStop()`, `Departures.aDeparture()`) with sensible defaults you can override.
  3. **Hand-written fakes** in `:core:data-test`, bound app-wide via `@TestInstallIn` so feature tests inherit them.
  4. **MockK**, last resort. Mocking a repository interface that already has a fake is a code smell.
- **Flow**: Turbine.
- **HTTP**: OkHttp `MockWebServer`. Never mock `OkHttpClient`.
- **DB**: Room in-memory.
- **Coroutines**: `kotlinx-coroutines-test` with `StandardTestDispatcher` for ViewModels (manual advance), `UnconfinedTestDispatcher` for repositories.
- **Compose UI**: `createComposeRule` for module-local tests, `createAndroidComposeRule<HiltComponentActivity>()` for Hilt-injected screens.
- **Screenshot**: Roborazzi on `:core:designsystem` and per-feature smoke screens.
- **Coverage gates** (CI): line coverage at least 80% on `:core:*`; at least 85% on `:feature:*` ViewModels; UI tests cover the golden path per feature.

## Theming

Material 3 with dynamic colour on Android 12+. Below that, fall back to a hand-tuned palette borrowed from ReadYou (`MaterialYouStandard.kt` and palette extraction code, Apache 2.0 compatible) so non-dynamic-colour devices feel intentional. The theme is owned by `:core:designsystem` and exposed as `OpenPtvTheme { content() }`.

## GitHub SDLC

GitHub repository: itsjfx/openptv

GitHub is the tool for SDLC. You will be working on tasks based on a GitHub issue. If it's not clear, ask questions.

Raise PRs, link back to the GitHub issue.

## Issue Template

When creating issues use the following template: What, Why, How, Acceptance Criteria, Out of Scope, Definition of Done

## Commits

All commits must include a Co-Authored-By trailer for the AI contributor:

Co-Authored-By: ai-tiro <ai-tiro@jfx.ac>

Make regular, small commits when accomplishing a small milestone within a ticket.

Push to a branch and make a draft PR as soon as possible. When complete, mark the PR as ready to review.

Put in the PR description: what you did, what you discovered, anything you tried that didn't work, and justify why you did what you did. Note any one-way door changes, or testing concerns.

If you're stuck, tag `@itsjfx` on the PR with your query before marking as ready.

When done, assign the PR to `@itsjfx` for review.

## Platform constraint: no Google Play services

The mobile app MUST run on **GrapheneOS**, so it cannot depend on Google Play services (GMS), Firebase, FCM, Play Billing, or anything that requires the Play Store. Use AOSP-friendly alternatives (UnifiedPush instead of FCM, MapLibre/OSM instead of Google Maps, `LocationManager` instead of Fused Location, etc.).

Because of this, the local + CI test target is the **AOSP system image** (`system-images;android-XX;default;x86_64`), not `google_apis*` — that way anything that secretly depends on GMS fails locally just like it would on GrapheneOS.

## Mobile testing workflow

When you make changes to the mobile app, test them on the AOSP emulator via the `mobile-mcp` MCP server before marking the PR ready:

1. **Get an emulator running.** Check for one with `mobile_list_available_devices`. If nothing's there, boot the AOSP AVD (`pixel_api36`); if that AVD doesn't exist, create it from `system-images;android-XX;default;x86_64` and boot it. Launch notes (KVM group, `LD_LIBRARY_PATH`) are in the auto-memory.
2. **Build and install:** `./gradlew :app:assembleDebug` then `adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk`.
3. **Exercise the change** with mobile-mcp tools — `mobile_list_elements_on_screen` to find what to tap, `mobile_click_on_screen_at_coordinates` / `mobile_type_keys` / `mobile_swipe_on_screen` to drive the UI.
4. **Take screenshots** at each meaningful state with `mobile_save_screenshot` to `/tmp/<descriptive-name>.png`.
5. **Post the screenshots (and the debug APK, when useful) to the PR via the `pr-attach` skill** (`~/.claude/skills/pr-attach/`). Do not commit PNGs or APKs into the repo. The skill uploads everything to a secret gist named `<repo>#<pr>` and posts one PR comment with embedded image markdown and an APK download link, so reviewers can see the change and install the build without booting an emulator. It handles binary-file quirks that `gh gist create` doesn't: gist rejects PNG/APK uploads directly, defaults its branch to `main` not `master`, and 404s on unpinned raw URLs for files ≥ ~10 MB.
