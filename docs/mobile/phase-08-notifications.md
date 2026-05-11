# Mobile Phase 8 — Disruption notifications

> Goal: notify the user when a route serving one of their favourite stops has a current disruption. Local-only — no FCM.

**Depends on:** Phase 4 (favourites), Phase 7 (WorkManager + sync module exist).
**Blocks:** none.

## Scope

A periodic worker polls the proxy for disruptions affecting routes serving favourite stops. New disruptions raise notifications. Resolved disruptions cancel them.

## Deliverables

### `:core:domain` additions
- [ ] `Disruption(id, title, description, status, type, lastUpdated, fromDate, toDate, routes, stops, urls)`.
- [ ] `DisruptionRepository.observeForFavourites(): Flow<List<Disruption>>`.

### `:core:network` additions
- [ ] `BackendApiService.disruptionsForRoute(routeId)`.
- [ ] `BackendApiService.disruption(disruptionId)`.

### `:core:database` additions
- [ ] `KnownDisruptionEntity` (id, lastShown, dismissed) — to dedupe notifications across worker runs.

### `:sync`
- [ ] `DisruptionPollWorker`: 30-minute periodic, batched per favourite-route, respects backend cache (no rapid-fire).

### Notifications
- [ ] Channel `disruptions` (importance `HIGH`).
- [ ] Android-13 `POST_NOTIFICATIONS` permission flow on first-time launch after this phase ships.
- [ ] Per-disruption notification with deep-link to disruption detail (Phase 10) or route detail.
- [ ] Group + summary when 3+ disruptions arrive within 1 hour.

## Out of scope

- FCM / server-pushed notifications. Polling only.
- Notification scheduling tied to user calendar.
- Per-route mute settings (consider for Phase 11).

## Acceptance criteria

- A new disruption on a route the user follows raises a notification within 30 min ± Doze tolerance.
- Tapping the notification deep-links to disruption detail.
- The same disruption never fires twice.
- A resolved disruption cancels the active notification automatically.
- Denying notification permission gracefully degrades — disruptions still surface in-app on the favourites screen.

## Test plan

- `:core:data` — repository merges disruption results across favourites, dedupes by id.
- `:sync` — `DisruptionPollWorkerTest`: first run inserts known set, subsequent runs only notify diffs, dismissed entries don't reappear.
- Manual: trigger via fake disruption returned from a local dev backend; observe notification, dismissal, re-emission.

## Implementation notes

- The PTV `disruptions/route/{route_id}` endpoint can return long bodies; lean on the backend cache (TTL 60 s).
- Don't poll faster than 30 min; PTV updates aren't real-time and Doze will throttle anyway.
- Permission rationale: explain "we only notify for disruptions on stops you've starred — never marketing".

## References

- PTV API: `/v3/disruptions/route/{route_id}`
- [Notifications best practices](https://developer.android.com/develop/ui/views/notifications/build-notification)
