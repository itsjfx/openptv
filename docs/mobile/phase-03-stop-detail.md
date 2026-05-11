# Mobile Phase 3 — Stop detail and live departures

> Goal: tap a stop in search results, see its routes and live upcoming departures with relative times.

**Depends on:** Mobile Phase 2 (search → navigates here).
**Blocks:** Mobile Phase 4 (favourites' "star" lives on this screen), Phase 6 (routes).

## Scope

Add `:feature:stop-detail`. On entry, load stop metadata + departures concurrently. Poll departures every 30 s while the screen is in `Lifecycle.State.RESUMED`. Format times relative to now.

## Deliverables

### `:core:domain` additions
- [ ] `StopDetail` (stop + serving routes).
- [ ] `Departure` (route, run ref, scheduled, estimated, platform, direction, flags).
- [ ] `Direction`, `RunRef`, `PlatformNumber`.
- [ ] Repository **interfaces** (per NIA, in `:core:data`): `StopDetailRepository`, `DepartureRepository`.
- [ ] UseCases (`:core:domain`): `GetStopDetailUseCase`, `ObserveDeparturesUseCase` (returns `Flow<Result<List<Departure>>>` that re-emits on a 30 s tick; emits `Result.Loading` between ticks while a refresh is in flight).

### `:core:network` additions
- [ ] `BackendApiService.getStop(id, routeType)`.
- [ ] `BackendApiService.getDepartures(routeType, stopId, expand=Run,Direction,Route,Disruption)`.
- [ ] DTOs + mappers.

### `:core:data` additions
- [ ] Repository implementations.
- [ ] `DepartureRepositoryImpl.observeDepartures()` ticks via `flow { while(...) { ... ; delay(30_000) } }`, cancels when collector cancels.

### `:feature:stop-detail`
- [ ] `StopDetailScreen`: stop header (name, suburb, mode icon), route chips, scrollable departures list grouped by route.
- [ ] Each departure row: route badge, destination, "in 3 min" + scheduled time, platform, delay indicator (+2 min), disruption flag.
- [ ] Pull-to-refresh.
- [ ] `StopDetailViewModel` with sealed `StopDetailUiState`.
- [ ] Lifecycle-aware polling: collect via `repeatOnLifecycle(Lifecycle.State.RESUMED)`.

### `:core:common` additions
- [ ] `RelativeTimeFormatter` ("now", "in 3 min", "in 1 h 12 min", "delayed", "departed").
- [ ] Locale-aware via `Clock.System.now()`; injectable `Clock` for tests.

## Out of scope

- Stopping pattern / "where does this run go" (Phase 9).
- Disruption detail screen (Phase 10 — list link only here).
- Favouriting (Phase 4 wires the star icon already shown disabled here).

## Acceptance criteria

- Departures refresh every 30 s while screen is visible; pause when backgrounded; resume on return.
- Pull-to-refresh produces a single forced refresh and updates the "as of" timestamp.
- Departed entries (estimated < now) drop off; new ones appear smoothly.
- Disruption indicator on a route opens a placeholder snackbar (link target lands in Phase 10).
- TalkBack reads each row as "Route 19 to North Coburg, departing in 3 minutes from platform 2".

## Test plan

- `:core:common`
  - `RelativeTimeFormatterTest`: all branches (now, minutes, hours, days, past, null estimated).
- `:core:data`
  - `DepartureRepositoryImplTest` with MockWebServer + virtual time: tick produces fresh result; collector cancellation stops the tick.
- `:feature:stop-detail`
  - ViewModel test with virtual time and `Clock` injection: state sequence, error mid-poll surfaces and recovers, lifecycle pauses ticking.
  - Compose UI: loading skeleton, results, empty (last service of the day), error retry.
  - Roborazzi: 3 themes × loading/results/empty/error.
- Manual: open Flinders Street on a weekday afternoon, observe live updates over 5 minutes.

## Implementation notes

- PTV's `/v3/departures` returns a flat list across many routes when `route_id` is omitted. Group client-side by route + direction.
- `expand=All` is overkill; pick `Run,Direction,Route,Disruption` to minimise payload.
- `estimated_departure_utc` may be null (no real-time prediction available). Fall back to `scheduled_departure_utc` and label "scheduled".
- Don't poll faster than 30 s — wastes battery and your PTV quota. Backend caches at 15 s anyway.

## References

- PTV Departures API: `GET /v3/departures/route_type/{route_type}/stop/{stop_id}`
- [Lifecycle-aware Flow collection](https://developer.android.com/topic/libraries/architecture/coroutines#restart)
