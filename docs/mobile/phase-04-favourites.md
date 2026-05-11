# Mobile Phase 4 — Favourites (Room + DataStore)

> Goal: star a stop, see it in a Favourites screen, drag to reorder, swipe to remove. Local-only — no cloud sync.

**Depends on:** Mobile Phase 3 (star action lives in stop detail header).
**Blocks:** Mobile Phase 7 (Glance widget reads favourites), Phase 8 (notifications poll favourite routes).

## Scope

Add a small persistence layer (Room + DataStore) and a `:feature:favourites` screen. This is the first phase that introduces local DB; treat the schema and migrations carefully — the widget will depend on it.

## Deliverables

### `:core:database`
- [ ] Room database `OpenPtvDatabase` v1.
- [ ] Entity `FavouriteStopEntity` (`stopId`, `routeType`, `name`, `suburb`, `lat`, `lng`, `position`, `addedAt`).
- [ ] DAO `FavouriteStopDao` with `observeAll(): Flow<List<FavouriteStopEntity>>`, `upsert`, `delete`, `reorder(ids: List<Int>)`.
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
- [ ] `FavouriteStop` domain type.
- [ ] `FavouritesRepository` interface (`observe`, `add(StopId, RouteType)`, `remove`, `reorder`, `isFavourite`).
- [ ] UseCases: `ToggleFavouriteUseCase`, `ObserveFavouritesUseCase`, `ReorderFavouritesUseCase`.

### `:core:data` additions
- [ ] `FavouritesRepositoryImpl` mapping entity ↔ domain.

### `:feature:favourites`
- [ ] `FavouritesScreen`: list of favourites with "in 4 min next" subtext, drag handles, swipe-to-delete.
- [ ] Empty state with "Search for a stop to favourite" CTA → navigates to search.
- [ ] Sort selector (manual / alphabetical / nearest — nearest disabled until Phase 5 lands location).
- [ ] Tap row → stop detail.
- [ ] `FavouritesViewModel` combining `ObserveFavouritesUseCase` and the **next** departure per favourite (single API call per favourite, batched).

### `:feature:stop-detail` additions
- [ ] Star icon in the top app bar; tapping it toggles via `ToggleFavouriteUseCase`. Animated fill state.

### Settings (lightweight start)
- [ ] `:feature:settings` skeleton with theme + dynamic-colour toggles wired to DataStore.

## Out of scope

- Cloud sync. Permanently out of scope unless the project ever wants accounts.
- Per-favourite custom names (stop alias). Phase 11 polish if requested.
- Folders / grouping.

## Acceptance criteria

- Starring a stop persists across cold launch.
- Reordering by drag persists.
- Swipe-delete shows an undo snackbar; tapping undo restores at the original position.
- Favourites screen shows next departure per row, refreshes every 60 s while visible.
- Migration test scaffolding runs (no migrations yet, but `MigrationTestHelper` lives in the project).

## Test plan

- `:core:database`
  - `FavouriteStopDaoTest` (in-memory): upsert, observe, reorder, delete.
  - `OpenPtvDatabaseMigrationTest` placeholder.
- `:core:datastore`
  - `UserPreferencesDataStoreTest` with a temp directory.
- `:core:data`
  - `FavouritesRepositoryImplTest` with MockK DAO.
- `:feature:favourites`
  - ViewModel: empty → results, reorder, delete + undo, sort change re-emits.
  - Compose UI: drag-to-reorder smoke (Espresso), swipe-delete + undo.
  - Roborazzi: empty, loaded (3 entries), reorder mode.
- `:feature:stop-detail`
  - Star toggles ViewModel state; UI test asserts fill animation begins.

## Implementation notes

- Room `@Query("UPDATE favourites SET position = :pos WHERE stopId = :id")` runs inside a transaction during reorder; do this in a single `@Transaction` method that takes `List<Pair<Int, Int>>` to avoid intermediate constraint violations.
- `Flow<List<FavouriteStopEntity>>` from Room is conflated by default. That's fine for a UI list but write a test that asserts re-emission on edit.
- Avoid `Migration` boilerplate before you need it — but commit the `schemas/` directory now, so the next phase that bumps the schema gets a proper diff.
- For the "next departure per favourite" batch, fan out N parallel calls bounded by a `Semaphore(4)` to stay polite to the proxy.

## References

- [Room with Hilt](https://developer.android.com/training/dependency-injection/hilt-android#predefined-bindings)
- [Preferences DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- ReadYou `app/src/main/java/me/ash/reader/infrastructure/preference/` — typed Preference DSL precedent
- ReadYou `infrastructure/preference/AccountSettings.kt:21-37` — composition-local provider pattern
