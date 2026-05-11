# OpenPTV docs

Specification and phase plan for the OpenPTV project.

- **[architecture.md](architecture.md)** — system design, locked stack decisions, repo layout, threat model, phase summary.

## Phases

Each row below maps to a single GitHub issue. Phase docs are self-contained and importable as-is.

### Mobile

| #   | Title                              | File                                                       | Depends on |
| --- | ---------------------------------- | ---------------------------------------------------------- | ---------- |
| —   | Conventions                        | [mobile/00-conventions.md](mobile/00-conventions.md)       |            |
| 01  | Project skeleton & theme           | [mobile/phase-01-skeleton.md](mobile/phase-01-skeleton.md) | —          |
| 02  | Networking & stop search           | [mobile/phase-02-search.md](mobile/phase-02-search.md)     | M01, B01   |
| 03  | Stop detail & live departures      | [mobile/phase-03-stop-detail.md](mobile/phase-03-stop-detail.md) | M02   |
| 04  | Favourites (Room + DataStore)      | [mobile/phase-04-favourites.md](mobile/phase-04-favourites.md) | M03    |
| 05  | Nearby stops map                   | [mobile/phase-05-nearby-map.md](mobile/phase-05-nearby-map.md) | M03, M04 |
| 06  | Routes                             | [mobile/phase-06-routes.md](mobile/phase-06-routes.md)     | M03        |
| 07  | Glance widget — next departure     | [mobile/phase-07-widget.md](mobile/phase-07-widget.md)     | M04        |
| 08  | Disruption notifications           | [mobile/phase-08-notifications.md](mobile/phase-08-notifications.md) | M04, M07 |
| 09  | Stopping pattern                   | [mobile/phase-09-stopping-pattern.md](mobile/phase-09-stopping-pattern.md) | M03 |
| 10  | Disruption browser                 | [mobile/phase-10-disruptions.md](mobile/phase-10-disruptions.md) | M08    |
| 11  | Polish, accessibility, performance | [mobile/phase-11-polish.md](mobile/phase-11-polish.md)     | M01–M10    |

### Backend (Go)

| #   | Title                              | File                                                       | Depends on |
| --- | ---------------------------------- | ---------------------------------------------------------- | ---------- |
| —   | Conventions                        | [backend/00-conventions.md](backend/00-conventions.md)     |            |
| 01  | Proxy MVP (HMAC signer)            | [backend/phase-01-proxy-mvp.md](backend/phase-01-proxy-mvp.md) | —      |
| 02  | In-memory caching                  | [backend/phase-02-caching.md](backend/phase-02-caching.md) | B01        |
| 03  | Observability (metrics, logs)      | [backend/phase-03-observability.md](backend/phase-03-observability.md) | B02 |
| 04  | Hardening, Dockerfile, CI          | [backend/phase-04-hardening.md](backend/phase-04-hardening.md) | B03    |
| 05  | Deploy + edge (Cloudflare/nginx)   | [backend/phase-05-deploy.md](backend/phase-05-deploy.md)   | B04        |

## Cross-cutting dependencies

- Mobile Phase 02 unblocks once Backend Phase 01 is reachable (use a local dev instance).
- Backend Phases 02–05 are independent of mobile work; ship them in parallel.
- Polish (Mobile 11) and Deploy (Backend 05) can interleave; both are end-of-stream phases.

## Issue import

Each phase doc has a stable structure (title → goal → scope → deliverables checklist → acceptance → tests). Suggested workflow once the project is on GitHub:

1. Create labels: `mobile`, `backend`, `phase:01`–`phase:11`, `epic`.
2. For each `phase-NN-<slug>.md`, run `gh issue create --title "$(head -n1 file.md)" --body-file file.md --label mobile,phase:NN`.
3. Pin the architecture doc as a discussion or wiki page; not an issue.

A small script `scripts/import-issues.sh` can be added in Phase 01 if desired.
