# Mobile Phase 7 — Glance widget: next departure

> Goal: a home-screen widget that shows the next 1–3 departures for the user's first favourite stop, refreshed in the background.

**Depends on:** Phase 4 (favourites are the data source), Phase 3 (departure formatting).
**Blocks:** none.

## Scope

Introduce WorkManager + Glance for the first time. Keep the widget read-only (taps deep-link into the app). Refresh on a `PeriodicWorkRequest` and on user-triggered tap-to-refresh.

## Deliverables

### `:feature:widget`
- [ ] `NextDepartureWidget : GlanceAppWidget` rendering 3 layouts: small (1×1), wide (3×1), large (3×2).
- [ ] `NextDepartureWidgetReceiver : GlanceAppWidgetReceiver`.
- [ ] Configuration screen at install time: pick which favourite to bind to (default: first).

### `:sync:work` (new module)
- [ ] WorkManager `WidgetRefreshWorker` (Hilt-Worker via `androidx.hilt:hilt-work`). Reference impl: ReadYou `infrastructure/di/WorkerModule.kt`.
- [ ] Periodic 15-minute schedule, plus `OneTimeWorkRequest` triggered when the app updates favourites.
- [ ] Backoff: linear 30 s on transient errors.
- [ ] `SyncInitializer : androidx.startup.Initializer<Unit>` registers the periodic work without touching `Application.onCreate` — NIA pattern (`sync/work/.../SyncInitializer.kt`).

### `:sync:sync-test` (new module)
- [ ] `NeverSyncingSyncManager` fake bound app-wide via `@TestInstallIn` so feature tests don't drag WorkManager into their classpath. Direct port of NIA `sync/sync-test/.../NeverSyncingSyncManager.kt`.

### Manifest + Hilt
- [ ] Custom `WorkerFactory` provided by Hilt; `Configuration.Provider` on `OpenPtvApplication`.
- [ ] Glance widget metadata in `xml/next_departure_widget_info.xml`.

## Out of scope

- Multi-favourite carousel widget (Phase 11 polish).
- Wear OS / Tiles. Out of scope indefinitely.

## Acceptance criteria

- After adding the widget and starring a stop, the widget shows the next departure within ≤30 s.
- Refresh happens automatically every 15 min (Doze-aware) and immediately when the user taps the refresh icon.
- Tapping a departure deep-links into stop detail with the correct stop selected.
- Widget renders identically across light/dark/dynamic colour themes.

## Test plan

- `:sync` — `WidgetRefreshWorkerTest` with `WorkManagerTestInitHelper`: success path, transient failure → backoff, no-favourites short-circuit.
- `:feature:widget` — Glance instrumentation tests asserting state per layout (Roborazzi screenshot tests on Glance are limited; rely on manual visual checks too).
- Manual: install on Pixel + Galaxy; verify each widget size; verify after 24h of being idle.

## Implementation notes

- Glance state is keyed by widget instance id — store the bound `StopId` in `GlanceStateDefinition` (Preferences or Proto).
- Don't poll faster than 15 min; respect `WorkManager.PeriodicWorkRequest` minimum (15 min).
- F-Droid concern: this phase pulls no proprietary deps. Safe.

## References

- [Glance documentation](https://developer.android.com/develop/ui/compose/glance)
- [Hilt + WorkManager](https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager)
- ReadYou widget precedent (worth opening side-by-side while implementing):
  - `app/src/main/java/me/ash/reader/ui/widget/ArticleCardWidget.kt` — minimal Glance widget structure
  - `app/src/main/java/me/ash/reader/ui/widget/ArticleListWidget.kt` — list-shaped widget
  - `app/src/main/java/me/ash/reader/ui/widget/WidgetConfigActivity.kt` — install-time config screen
  - `app/src/main/java/me/ash/reader/ui/widget/WidgetRepository.kt` — state persistence pattern
- NIA `sync/work/.../SyncInitializer.kt` — App Startup pattern for queueing periodic work
- NIA `sync/sync-test/.../NeverSyncingSyncManager.kt` — sync fake for feature tests
