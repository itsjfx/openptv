# Mobile Phase 2 — Networking + stop search

> Goal: search PTV stops and routes from a Compose screen, end-to-end through the Go proxy.

**Depends on:** Mobile Phase 1, Backend Phase 1 (proxy must be reachable).
**Blocks:** Mobile Phase 3 (stop detail), Phase 4 (favourites).

## Scope

Wire up the network stack, add the first feature module (`:feature:search`), and prove the architecture across a single feature: ViewModel → UseCase → Repository (interface in `:core:domain`, impl in `:core:data`) → API in `:core:network` → Go proxy.

## Deliverables

### `:core:network`
- [ ] OkHttp `OkHttpClient` provided by Hilt; reasonable timeouts (15 s connect, 30 s read), HTTP logging in debug.
- [ ] Retrofit `Retrofit` provided by Hilt with kotlinx.serialization converter.
- [ ] `BackendBaseUrl` qualifier annotation; URL injected from `BuildConfig` (debug → local proxy, release → openptv.app).
- [ ] `BackendApiService` interface with `searchStops(term: String): SearchResponse`.
- [ ] DTOs marked `@Serializable`, internal to module.
- [ ] DTO → domain mapper.
- [ ] Network call extension that maps thrown exceptions into the project-wide `Result<T>` (sealed: `Success<T>`, `Error(Throwable)`, `Loading`) — pattern lives in `:core:common` (added this phase) and matches NIA's `core/common/.../result/Result.kt`. Wraps `IOException`, HTTP errors, and `SerializationException` as the `Error` `Throwable` payload.

### `:core:model`
- [ ] Domain types: `Stop`, `Route`, `RouteType`, `StopId`, `RouteId`. Pure data classes, no Android deps.

### `:core:data` (interfaces + impls per NIA)
- [ ] `StopSearchRepository` interface.
- [ ] `StopSearchRepositoryImpl` calls `BackendApiService`, maps DTOs, returns `Result<List<Stop>>`. Wraps DTO mapping errors as `Result.Error`.
- [ ] Hilt `RepositoryModule` binds interface → impl.

### `:core:domain`
- [ ] `SearchStopsUseCase` (single `operator fun invoke(query: String): Flow<Result<List<Stop>>>`).

### `:feature:search`
- [ ] `SearchScreen` Compose: text field, debounced query (~300 ms), result list, empty/loading/error states.
- [ ] `SearchViewModel`: `StateFlow<SearchUiState>` with `Idle`, `Loading`, `Empty`, `Results(stops)`, `Error(reason)`. ViewModel maps incoming `Result<List<Stop>>` into the `UiState` shape — `Result.Loading` → `Loading`, `Result.Success(list)` → `Empty`/`Results`, `Result.Error(t)` → `Error(t.toUserFacingReason())`.
- [ ] Tap on result emits navigation event (target screen lands in Phase 3 — for now log and show a snackbar).
- [ ] Accessibility: TalkBack reads each result with stop name + suburb + transport mode.

### `:core:testing`
- [ ] Object Mothers: `Stops.aStop()`, `Routes.aRoute()`, `SearchResults.someStops()` — pure domain-type fixtures, no test-double behaviour.

### `:core:data-test`
- [ ] Hand-written `FakeStopSearchRepository` backed by an in-memory list, with helpers (`returns(...)`, `failsWith(...)`).
- [ ] `@TestInstallIn` Hilt module bound app-wide so feature androidTests pick up the fake automatically.

## Out of scope

- Stop detail screen and departures (Phase 3).
- Favouriting from search results (Phase 4).
- Recent searches / history (Phase 11 polish).

## Acceptance criteria

- Typing a query for ≥3 characters triggers a search after debounce; backspacing cancels the in-flight call.
- Results render with stop name, suburb, and a small transport-mode icon.
- 4xx / 5xx / network failure / decoding error each produce a distinct visible message; the screen recovers when the user retries.
- A successful search of "flinders" returns Flinders Street and reasonable variants on a real device against the staging proxy.

## Test plan

- `:core:network`
  - `BackendApiServiceTest` against `MockWebServer`: success, 4xx, 5xx, malformed JSON each surface as `Result.Error` with a discriminating `Throwable` subtype.
  - `SearchDtoMapperTest` (real mapper, no doubles) covers all enum cases for transport mode.
- `:core:data`
  - `StopSearchRepositoryImplTest` with `MockWebServer` (real `BackendApiService` constructed against the test URL — no MockK needed): asserts each upstream condition produces the right `Result`.
- `:feature:search`
  - `SearchViewModelTest` with `StandardTestDispatcher`, Turbine: debounce behaviour, cancellation on new query, error → idle on retry.
  - Compose UI test: typing → loading → results; error state shows retry button.
  - Roborazzi screenshots: idle, loading, results (3 entries), empty, error.
- Coverage gate: `:feature:search` ≥85%.

## Implementation notes

- Debounce with `flatMapLatest` over a `MutableStateFlow<String>` — cancels prior emission when query changes.
- The PTV `/v3/search/{term}` endpoint returns stops, routes, and outlets. For Phase 2 we only render stops; routes appear in Phase 6.
- The Go proxy strips the `signature` query param from inbound requests and re-signs upstream. The mobile client should never pass `devid` or `signature`.

## References

- PTV Search API: `GET /v3/search/{search_term}` ([swagger](https://timetableapi.ptv.vic.gov.au/swagger/docs/v3))
- Backend Phase 1 — proxy MVP
