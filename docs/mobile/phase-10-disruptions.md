# Mobile Phase 10 — Disruption browser

> Goal: full list of active disruptions across followed routes, filterable, with detail screen.

**Depends on:** Phase 8 (data layer for disruptions exists).
**Blocks:** none.

## Scope

A dedicated `:feature:disruptions` screen surfaces every active disruption that touches the user's favourites or, optionally, all of Victoria. Detail screen renders the disruption body + affected routes/stops with deep links.

## Deliverables

- [ ] `DisruptionsScreen` — list grouped by severity (Major / Minor / Planned).
- [ ] Filter chips: all / favourites only, by mode (train, tram, bus).
- [ ] `DisruptionDetailScreen` — title, status, dates, description (rendered as paragraphs, no HTML), affected routes + stops with deep links.
- [ ] Entry from disruption notifications (Phase 8) lands directly here.
- [ ] Bottom navigation gains a "Disruptions" tab once this phase ships (was hidden until now).

## Out of scope

- Reading official PTV disruption RSS feeds. The API is enough.

## Acceptance criteria

- Filter changes update the list without a full reload.
- Detail screen back-stack returns to the list with scroll position preserved.
- Empty state ("no disruptions affecting your favourites") is not the only empty state — also handle "no active disruptions in Victoria" gracefully.

## Test plan

- ViewModel state machine, filter combinations.
- Compose UI smoke; Roborazzi for grouped list, detail, empty.

## References

- PTV API: `/v3/disruptions`, `/v3/disruptions/{disruption_id}`.
