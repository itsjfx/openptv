# Mobile Phase 6 — Routes

> Goal: from a stop, drill into a serving route and see its stops list. Optional: a thin route shape on the map.

**Depends on:** Mobile Phase 3 (stop detail links to route), Phase 5 (route shape on map is a stretch).
**Blocks:** Phase 9 (stopping pattern uses route stops list).

## Scope

Add a Route detail screen and the data layer for it. Show: route name, route number, mode, ordered stops list, optional shape polyline on a small embedded map.

## Deliverables

### `:core:domain` additions
- [ ] `RouteDetail`, `Direction`, `RouteStop`.
- [ ] `RouteRepository.getRoute(routeId)`, `RouteRepository.getDirections(routeId)`, `RouteRepository.getStopsForRoute(routeId, directionId)`.

### `:core:network` additions
- [ ] `BackendApiService.getRoute(routeId)`.
- [ ] `BackendApiService.getDirectionsForRoute(routeId)`.
- [ ] `BackendApiService.getStopsForRoute(routeType, routeId, directionId, geopath=true)`.

### `:feature:routes`
- [ ] `RouteDetailScreen`: header (route badge, name, mode), direction switcher (e.g. "Towards City" / "Towards Frankston"), ordered stops list with tap → stop detail.
- [ ] Stretch: small embedded `OpenPtvMap` with the route polyline (from `geopath`) and stop markers.
- [ ] Search results from Phase 2 now also link to route detail (today they only link to stops).

## Out of scope

- Editing routes / favouriting routes (phase 8 implies users follow a route for disruption alerts, not favourite it directly).
- Route schedules. Use Phase 9's stopping-pattern view.

## Acceptance criteria

- Tapping a route chip on stop detail opens route detail with the correct direction pre-selected.
- Direction switcher reorders stops correctly.
- If `geopath` is unavailable for a route, the embedded map is hidden (not broken).

## Test plan

- `:core:data` — repository test with MockWebServer.
- `:feature:routes` — ViewModel state tests (loading, results, switch direction); Compose UI smoke; Roborazzi for both directions.

## References

- PTV API: `/v3/routes/{route_id}`, `/v3/directions/route/{route_id}`, `/v3/stops/route/{route_id}/route_type/{route_type}`.
