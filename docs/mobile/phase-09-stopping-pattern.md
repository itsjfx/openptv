# Mobile Phase 9 — Stopping pattern (where does this run go?)

> Goal: tap a departure → see the timeline of every stop the run will call at, with relative times.

**Depends on:** Phase 3 (stop detail surfaces departures with `runRef`).
**Blocks:** none.

## Scope

A vertical timeline view per `runRef`: stops in order, scheduled and estimated times, "you are here" marker, disruption badges on affected stops.

## Deliverables

### `:core:domain` additions
- [ ] `StoppingPattern(runRef, departures: List<Departure>, disruptions: List<Disruption>)`.
- [ ] `RunRepository.stoppingPattern(runRef, routeType): Result<StoppingPattern>`.

### `:core:network` additions
- [ ] `BackendApiService.runPattern(runRef, routeType, expand=Stop,Run,Direction,Disruption)`.

### `:feature:run-pattern`
- [ ] `RunPatternScreen`: timeline list with vertical line, dots, alternating "platform N" annotations.
- [ ] Live polling every 30 s while visible (same primitive as Phase 3).
- [ ] Tap any stop in the timeline → stop detail.

## Out of scope

- Editing / sharing the pattern.
- Map view of the run's path (could fold into Phase 6 stretch).

## Acceptance criteria

- For a Frankston train running, every intermediate stop is listed in order with platform numbers and either scheduled or estimated time.
- The "you are here" indicator is placed correctly given the live position (best-effort: between the latest passed stop and the next upcoming).
- Disruption badge visible on affected stops.

## Test plan

- `:core:data` — repository test (MockWebServer).
- `:feature:run-pattern` — ViewModel + Compose UI test; Roborazzi for the timeline rendering.

## References

- PTV API: `/v3/pattern/run/{run_ref}/route_type/{route_type}`
