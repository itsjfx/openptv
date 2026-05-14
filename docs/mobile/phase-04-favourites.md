# Mobile Phase 4 — Favourites (Room + DataStore)

> Goal: star a route at a stop, see it in a Favourites screen with the next departure for that specific service, drag to reorder, swipe to remove. Tap a favourite to open the existing stop-detail departures view filtered to that one route+direction. Local-only — no cloud sync.

**Depends on:** Mobile Phase 3 (departures grouped by `(routeId, directionId)` — the star sits on each group).
**Blocks:** Mobile Phase 7 (Glance widget reads favourites), Phase 8 (notifications poll favourite routes).

## Scope

Add a small persistence layer (Room + DataStore) and a `:feature:favourites` screen. This is the first phase that introduces local DB; treat the schema and migrations carefully — the widget will depend on it.

The favourite unit is a **route at a stop** — the triple `(stopId, routeId, directionId)` — not a whole stop. The user's primary use case is "show me when the 19 northbound leaves my house stop", not "show me everything that leaves my house stop", so each favourited row corresponds to one service at one stop.

## Deliverables

### `:core:database`
- [ ] Room database `OpenPtvDatabase` v1.
- [ ] Entity `FavouriteRouteAtStopEntity` (table `favourite_routes_at_stop`):
  - Keys: `stopId`, `routeType`, `routeId`, `directionId` (composite primary key on `(stopId, routeId, directionId)`).
  - Cached display fields: `stopName`, `stopSuburb`, `routeNumber`, `routeName`, `directionName`, `lat`, `lng`.
  - List state: `position`, `addedAt`.
  - The cached display fields exist so the favourites list renders without a network call.
- [ ] DAO `FavouriteRouteAtStopDao`:
  - `observeAll(): Flow<List<FavouriteRouteAtStopEntity>>`
  - `upsert(entity): Unit`
  - `delete(stopId: Int, routeId: Int, directionId: Int): Unit`
  - `reorder(orderedIds: List<Triple<Int, Int, Int>>): Unit` — single `@Transaction`.
- [ ] Hilt module providing the database with `Room.databaseBuilder` (no `.fallbackToDestructiveMigration()`).
- [ ] Migration test infrastructure (empty for v1, but the framework is in place).
- [ ] Schema export under `core/database/schemas/`.

### `:core:datastore` — typed `Preference` DSL (ReadYou-style)
- [ ] Backing store: Preferences DataStore (not Proto for v1).
- [ ] One sealed `Preference<T>` per setting, each with `value`, `put(scope: CoroutineScope)`, and a companion default. Pattern from ReadYou `infrastructure/preference/`.
  - [ ] `ThemeModePreference` (sealed: `System`, `Light`, `Dark`)
  - [ ] `DynamicColourPreference` (sealed: `On`, `Off`)
  - [ ] `FavouritesSortPreference` (sealed: `Manual`, `Alphabetical`, `Nearest`)
- [ ] Each preference exposed via a `compositionLocalOf { default }` so Compose code reads `LocalThemeMode.current` instead of injecting a settings repo.
- [ ] `SettingsProvider` composable wraps app content once at root, collects all preferences, populates the locals — pattern from ReadYou `infrastructure/preference/AccountSettings.kt:21-37`.
- [ ] Hilt singleton wrapper around the DataStore for non-Compose consumers (workers, ViewModels that need to write).

### `:core:domain` additions
- [ ] `FavouriteRouteAtStop` domain type (mirrors the entity).
- [ ] `FavouritesRepository` interface:
  - `observe(): Flow<List<FavouriteRouteAtStop>>`
  - `add(stopId, routeType, routeId, directionId, stopName, stopSuburb, routeNumber, routeName, directionName, lat, lng): Unit`
  - `remove(stopId, routeId, directionId): Unit`
  - `reorder(orderedIds: List<Triple<Int, Int, Int>>): Unit`
  - `isFavourite(stopId, routeId, directionId): Flow<Boolean>`
- [ ] UseCases: `ToggleFavouriteUseCase` (takes `Stop` + `Route` + `Direction` or equivalent ids + display fields), `ObserveFavouritesUseCase`, `ReorderFavouritesUseCase`.

### `:core:data` additions
- [ ] `FavouritesRepositoryImpl` mapping entity ↔ domain.

### `:feature:favourites`
- [ ] `FavouritesScreen`: list of route-at-stop rows. Each row shows route badge + number, direction name, stop name + suburb, mode icon, and an "in N min" subtext for the **next departure of that specific route+direction at that stop**.
- [ ] Drag handles in manual sort mode; swipe-to-delete with undo snackbar.
- [ ] Empty state copy: "Star a route at a stop to favourite it" with a CTA that navigates to search.
- [ ] Sort selector (manual / alphabetical / nearest — nearest disabled until Phase 5 lands location). Alphabetical sorts by `stopName` then `routeNumber`.
- [ ] Tap row → existing stop-detail destination with two new optional ints `focusRouteId` + `focusDirectionId` so the screen renders only the matching `(routeId, directionId)` group.
- [ ] `FavouritesViewModel` combining `ObserveFavouritesUseCase` and the **next** departure per favourite. The next-departure call reuses the existing `ObserveDeparturesUseCase` against the favourite's stop, then filters in memory to its `(routeId, directionId)` — no new per-route departures endpoint in this phase.

### `:feature:stop-detail` additions
- [ ] Remove the disabled top-app-bar favourite IconButton placeholder.
- [ ] Add a star / star-outline affordance next to each `(routeId, directionId)` group header on the existing departures list. Tapping it toggles via `ToggleFavouriteUseCase` scoped to that route+direction at the current stop. Animated fill state.
- [ ] Render a single-group filtered view when the new `focusRouteId` + `focusDirectionId` nav args are non-null (so favourites tap-through reuses this screen).

### Settings (lightweight start)
- [ ] `:feature:settings` skeleton with theme + dynamic-colour toggles wired to DataStore.

## Out of scope

- Cloud sync. Permanently out of scope unless the project ever wants accounts.
- Per-favourite custom names (stop alias). Phase 11 polish if requested.
- Folders / grouping.
- Per-route departures endpoint at the data layer (would avoid the in-memory filter in the favourites VM). Bigger change; defer.

## Acceptance criteria

- Starring a route+direction on a stop persists across cold launch.
- Reordering by drag persists.
- Swipe-delete shows an undo snackbar; tapping undo restores at the original position.
- Favourites screen shows the next departure of the favourited route+direction per row, refreshes every 60 s while visible.
- Tapping a favourite opens stop-detail with only the matching `(routeId, directionId)` group visible.
- Migration test scaffolding runs (no migrations yet, but `MigrationTestHelper` lives in the project).

## Test plan

- `:core:database`
  - `FavouriteRouteAtStopDaoTest` (in-memory): upsert, observe, reorder, delete by composite key.
  - `OpenPtvDatabaseMigrationTest` placeholder.
- `:core:datastore`
  - `UserPreferencesDataStoreTest` with a temp directory.
- `:core:data`
  - `FavouritesRepositoryImplTest` with MockK DAO.
- `:feature:favourites`
  - ViewModel: empty → results, reorder, delete + undo, sort change re-emits, next-departure subtext picks the correct `(routeId, directionId)` from the stop's full departures list.
  - Compose UI: drag-to-reorder smoke (Espresso), swipe-delete + undo, tapping a row navigates to stop-detail with the focus args set.
  - Roborazzi: empty, loaded (3 entries), reorder mode.
- `:feature:stop-detail`
  - Group-header star toggles ViewModel state for the correct group only; UI test asserts fill animation begins.
  - With `focusRouteId` + `focusDirectionId` set, the screen renders only the matching group.

## Implementation notes

- Room `@Query("UPDATE favourite_routes_at_stop SET position = :pos WHERE stopId = :sid AND routeId = :rid AND directionId = :did")` runs inside a transaction during reorder; do this in a single `@Transaction` method that takes `List<Triple<Int, Int, Int>>` (alongside the position index) to avoid intermediate constraint violations.
- `Flow<List<FavouriteRouteAtStopEntity>>` from Room is conflated by default. That's fine for a UI list but write a test that asserts re-emission on edit.
- Avoid `Migration` boilerplate before you need it — but commit the `schemas/` directory now, so the next phase that bumps the schema gets a proper diff.
- For the "next departure per favourite" batch, fan out N parallel calls bounded by a `Semaphore(4)` to stay polite to the proxy. Each call hits the existing per-stop departures endpoint and filters to the favourited `(routeId, directionId)` in memory.

## Open questions

- **Group-header star vs. per-departure-row star** on stop-detail — the spec above proposes group-header (matches the data model granularity) but row-level may be more discoverable. cc @itsjfx
- **Filtered stop-detail vs. dedicated screen** for the tap-through from favourites — the spec re-uses stop-detail with `focusRouteId` + `focusDirectionId` to match the user's "same view as when we see a departure now" requirement, but a new route-at-stop screen is the cleaner separation. cc @itsjfx

## References

- [Room with Hilt](https://developer.android.com/training/dependency-injection/hilt-android#predefined-bindings)
- [Preferences DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- ReadYou `app/src/main/java/me/ash/reader/infrastructure/preference/` — typed Preference DSL precedent
- ReadYou `infrastructure/preference/AccountSettings.kt:21-37` — composition-local provider pattern
