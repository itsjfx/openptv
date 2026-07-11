# OpenPTV Architecture

> Open-source Android client + Go backend proxy for the PTV (Public Transport Victoria) Timetable API v3.
> Status: design in progress (2026-05-10).

## Vision

Provide a small, fast, ad-free, Material You alternative to the official PTV app. It must work without Google Services. The mobile client never sees the PTV signing key; a Go proxy signs requests upstream. Auth and rate-limiting are handled at the edge (Cloudflare or nginx), not by the application — the proxy stays "dumb" and stateless.

## Non-goals

- iOS, web, desktop. Android only at first; DI choice (Hilt) explicitly trades portability for ergonomics.
- Multi-leg trip planning (A→B with transfers). The PTV API doesn't expose one; building a network graph client-side is out of scope. *Direct* A→B journeys (one run, no transfer) are in scope — the journey planner (issue #204) derives them from departures + run patterns.
- Real-time vehicle positions. Not in PTV API v3 surface.
- Hosted multi-tenant SaaS. Each user runs the proxy themselves or trusts the OpenPTV-hosted instance.
- Account systems, sync across devices, social features.

## System diagram

```
┌────────────────────┐                ┌──────────────────────┐
│  Android client    │                │  Edge (Cloudflare /  │
│  (Compose, Hilt,   │   HTTPS  ──>   │  nginx) — auth, rate │
│   MapLibre, Room)  │                │  limit, TLS, caching │
└────────────────────┘                └──────────┬───────────┘
                                                 │
                                                 ▼
                                      ┌──────────────────────┐
                                      │  Go proxy (stdlib)   │
                                      │  HMAC-SHA1 signer +  │
                                      │  in-process LRU      │
                                      └──────────┬───────────┘
                                                 │
                                                 ▼
                                      ┌──────────────────────┐
                                      │  timetableapi.ptv.   │
                                      │  vic.gov.au /v3      │
                                      └──────────────────────┘

┌────────────────────┐
│  Tile server       │   the Android map renders directly against
│  OpenFreeMap (no   │   OpenFreeMap, no proxy needed (no API key).
│  key)              │
└────────────────────┘
```

The proxy never returns presigned URLs. PTV signatures don't expire — leaking one leaks the key forever.

## Locked stack decisions

| Concern             | Choice                                               | Why                                                                                                                                                                |
| ------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Mobile language** | Kotlin                                               | Modern Android default. Compose-native.                                                                                                                            |
| **Min SDK / target**| `minSdk 26` (Android 8.0) / `compileSdk 36`          | Same as ReadYou and NIA. ~95% of active devices, and Compose APIs work without compat shims.                                                                       |
| **UI**              | Jetpack Compose + Material 3 (Material You)          | Single declarative UI tree, dynamic colour, what ReadYou ships.                                                                                                    |
| **Compose BOM**     | `androidx.compose:compose-bom` (latest stable)       | Pinned via version catalog; every Compose dep imports through `platform(libs.androidx.compose.bom)`. Matches NIA + ReadYou.                                        |
| **Navigation**      | Navigation 3 (`androidx.navigation3:*`) + Nav 2 alongside | Navigation 3 for new code; Navigation 2 stays on the classpath for `navigation-testing`. Both NIA and ReadYou ship both.                                       |
| **DI**              | Hilt with KSP                                        | Compile-time graph validation, best-in-class testing rules (`@HiltAndroidTest`, `@BindValue`, `@UninstallModules`), Spring-friendly mental model.                  |
| **Async**           | Coroutines + Flow                                    | Standard since 2020.                                                                                                                                               |
| **HTTP**            | OkHttp 5 + Retrofit 2                                | Stable, idiomatic, low-overhead.                                                                                                                                   |
| **JSON**            | kotlinx.serialization                                | Compile-time, ~2k methods (vs Jackson's ~10k + kotlin-reflect 3 MB transitive). Retrofit converter mature.                                                         |
| **DB**              | Room 2.7+ with KSP                                   | Type-safe SQLite. Paging integration if needed. Schema export under `core/database/schemas/`.                                                                      |
| **Preferences**     | DataStore (Preferences) + ReadYou-style typed `Preference` DSL | Async, type-safe SharedPreferences successor. Each setting is a sealed class exposed as `compositionLocalOf` per ReadYou's pattern (`infrastructure/preference/`).|
| **Time**            | `kotlinx.datetime` (`Instant`, `LocalDateTime`)      | Aligns with NIA. Stable, multiplatform-ready, less rope than `java.time` + `kotlin.time` mix.                                                                      |
| **Maps**            | MapLibre Native via `maplibre-compose`               | osmdroid is **archived**; Organic Maps engine is not embeddable. MapLibre is BSD-3, foundation-backed (AWS/Meta/MapTiler), active.                                 |
| **Tile source**     | OpenFreeMap (no API key, OSM-based)                  | No proxy required for tiles. Pinned style URL; can swap to MapTiler / Stadia if SLA needed.                                                                        |
| **Image loading**   | Coil 2.7+                                            | Compose-native, Kotlin-first. NIA + ReadYou both on Coil 2; staying on the boring path. Re-evaluate Coil 3 in a Phase 11+ ADR.                                     |
| **Background work** | WorkManager 2.10 + `hilt-work` + `androidx.startup`  | NIA pattern: workers scheduled via `Initializer` rather than `Application.onCreate`. For Glance widget refresh and disruption-poll workers.                        |
| **Widget**          | Glance (Phase 7+)                                    | Compose-style widget DSL, replaces RemoteViews. Reference impl: ReadYou `ui/widget/`.                                                                              |
| **Notifications**   | NotificationCompat + WorkManager poll (Phase 8+)     | No FCM dependency at first — keeps OSS distribution clean.                                                                                                         |
| **Errors / outcome**| NIA-style `Result<T>` (sealed `Success<T>` / `Error(Throwable)` / `Loading`) | Aligns with NIA's `core/common/.../result/Result.kt`. Compose UI maps the three states to `UiState` directly.                              |
| **Logging**         | `androidx.tracing` + custom `Logger` interface       | Avoid Timber transitive (NIA also avoids it); one-page implementation in `:core:common`.                                                                           |
| **Lint / format**   | Spotless (ktlint formatting) + detekt (static analysis) + Dependency Guard | Spotless mirrors NIA's setup; detekt adds complexity/magic-number checks; Dependency Guard baselines transitive deps.                  |
| **Testing**         | JUnit 4 + Truth + Turbine + MockWebServer + Roborazzi | JUnit 4 because Compose UI rules and `androidx.test.ext.junit` are JUnit-4-only. Truth for assertions (NIA-canonical).                                            |
| **Test doubles**    | Real objects → Object Mothers → hand-written fakes → MockK (last resort) | Prefer construction over mocking. `:core:data-test` ships fakes via `@TestInstallIn`. MockK only when the seam is truly opaque.        |
| **Backend lang**    | Go (stdlib)                                          | Per spec. `net/http` Go 1.22+ pattern matching; `encoding/json`, `crypto/hmac`, `log/slog`.                                                                        |
| **Backend deploy**  | Deferred — produce a 12-factor binary                | Container image with multi-stage Dockerfile. Pick host (Fly, Cloud Run, VPS) at Phase 5.                                                                           |
| **Edge auth**       | Cloudflare or nginx — application stays dumb         | Rate-limit by IP, optional Turnstile / WAF rule.                                                                                                                   |
| **Distribution**    | GitHub Releases signed APK                           | F-Droid / IzzyOnDroid optional later.                                                                                                                              |
| **Repo layout**     | Monorepo                                             | `mobile/`, `backend/`, `docs/mobile/`, `docs/backend/`.                                                                                                            |
| **License**         | Apache 2.0 (top-level)                               | Compatible with all chosen deps.                                                                                                                                   |

## Repository layout

```
openptv/
├── README.md
├── LICENSE                         (Apache-2.0)
├── docs/
│   ├── architecture.md             (this file)
│   ├── adr/                        (Architecture Decision Records — added as we go)
│   ├── mobile/
│   │   ├── 00-conventions.md
│   │   ├── phase-01-skeleton.md
│   │   ├── …
│   │   └── phase-11-polish.md
│   └── backend/
│       ├── 00-conventions.md
│       ├── phase-01-proxy-mvp.md
│       └── …
├── mobile/                         (Android Gradle project root)
│   ├── settings.gradle.kts
│   ├── gradle/libs.versions.toml
│   ├── build-logic/                (convention plugins, NIA-style)
│   ├── app/
│   ├── benchmarks/                 (Phase 11 macrobenchmarks)
│   ├── core/
│   │   ├── common/
│   │   ├── data/                   (repository interfaces + impls)
│   │   ├── data-test/              (fakes used by feature tests)
│   │   ├── database/
│   │   ├── datastore/
│   │   ├── designsystem/
│   │   ├── domain/                 (use cases only)
│   │   ├── model/                  (pure data classes, no Android deps)
│   │   ├── navigation/             (top-level NavKey / route definitions)
│   │   ├── network/
│   │   ├── notifications/          (Phase 8)
│   │   ├── testing/                (object mothers, fake clocks, generic test infra)
│   │   └── ui/
│   ├── feature/
│   │   ├── disruptions/            (Phase 10)
│   │   ├── favourites/
│   │   ├── nearby/
│   │   ├── routes/                 (Phase 6)
│   │   ├── run-pattern/            (Phase 9)
│   │   ├── search/
│   │   ├── settings/
│   │   ├── stop-detail/
│   │   └── widget/                 (Phase 7)
│   ├── lint/                       (custom lint rules; Phase 11)
│   ├── sync/
│   │   ├── work/                   (Phase 7+: WorkManager workers, SyncInitializer)
│   │   └── sync-test/              (NeverSyncingSyncManager fake)
│   └── ui-test-hilt-manifest/      (Hilt-aware Activity for Compose UI tests)
└── backend/                        (Go module)
    ├── go.mod
    ├── cmd/openptvd/main.go
    ├── internal/
    │   ├── config/
    │   ├── ptv/
    │   ├── proxy/
    │   ├── cache/
    │   └── observe/
    ├── Dockerfile
    └── Makefile
```

## Mobile architecture

### Layers (per the [official guide](https://developer.android.com/topic/architecture))

```
        ┌───────────────────────────────┐
        │  UI Layer                     │  Compose screens, ViewModels
        │  :app, :feature:*             │  StateFlow<UiState>; UiState
        │                               │  maps from Result<T>
        ├───────────────────────────────┤
        │  Domain Layer (thin, optional)│  Use cases that orchestrate
        │  :core:domain                 │  multiple repositories
        ├───────────────────────────────┤
        │  Data Layer                   │  Repository interfaces +
        │  :core:data                   │  impls (single source of truth)
        │  :core:database               │  DAOs
        │  :core:datastore              │  Preferences
        │  :core:network                │  Retrofit + DTOs (internal)
        └───────────────────────────────┘
```

- **Unidirectional state flow.** ViewModels expose `StateFlow<UiState>` and accept events as method calls. UI is a function of state.
- **Single source of truth** for any piece of data — usually the database for owned data, the network repository for ephemeral data (departures).
- **Repository interface AND implementation both live in `:core:data`.** Use cases in `:core:domain` depend on the interface. This matches NIA (`core/data/.../repository/*Repository.kt`).
- **`Result<T>` (sealed: `Success<T>`, `Error(Throwable)`, `Loading`)** flows from repository to ViewModel. ViewModels map it into a screen-specific `UiState`. Same shape as NIA's `core/common/.../result/Result.kt`.
- **Domain models in `:core:model`** (pure data classes, no Android deps). Network DTOs stay internal to `:core:network` and never leak into `:core:domain` / `:core:data` interfaces.
- **Navigation routes in `:core:navigation`** so feature modules can navigate to each other's destinations without depending on each other.

### Module dependency rules

```
:app  ─►  :feature:*  ─►  :core:ui, :core:designsystem, :core:domain, :core:data, :core:navigation
:core:data        ─►  :core:network, :core:database, :core:datastore, :core:model, :core:common
:core:domain      ─►  :core:data (interfaces only), :core:model
:feature:*        ─►  :core:data (interfaces only); :core:domain for use cases
:core:data-test   ─►  :core:data, :core:model   (test-fixtures consumer)
:core:testing     ─►  :core:model               (object mothers for domain types)
```

Convention plugins in `build-logic/` enforce module conventions (NIA's set, renamed):

- `openptv.android.application` / `openptv.android.application.compose`
- `openptv.android.library` / `openptv.android.library.compose`
- `openptv.android.feature`
- `openptv.android.hilt`
- `openptv.android.room`
- `openptv.android.test` (for the macrobenchmark module)
- `openptv.android.lint`
- `openptv.jvm.library`
- `openptv.spotless`

### Testing philosophy

- **Test framework**: JUnit 4. Compose UI rules and `androidx.test.ext.junit` are JUnit-4-only; pretending otherwise produces friction.
- **Assertions**: Truth (NIA-canonical) for everything; `assertThat(result).isInstanceOf(Result.Success::class.java)`.
- **Test doubles, in priority order**:
  1. **Real objects** — for pure types (formatters, mappers), construct them directly.
  2. **Object Mothers** in `:core:testing` — `Stops.aStop()`, `Departures.aDeparture()`, with sensible defaults you can override.
  3. **Hand-written fakes** in `:core:data-test` — fake repositories backed by an in-memory list; bound app-wide via `@TestInstallIn` so feature tests inherit them.
  4. **MockK** — only when the seam is opaque or you genuinely need behaviour verification. Mocking a repository interface that already has a fake is a code smell.
- **Coroutines**: `kotlinx-coroutines-test` with `StandardTestDispatcher` for ViewModels (manual advance), `UnconfinedTestDispatcher` for repositories.
- **Flow**: Turbine.
- **HTTP**: OkHttp `MockWebServer`. Never mock `OkHttpClient`.
- **DB**: Room in-memory.
- **Compose UI**: `createComposeRule` for module-local tests, `createAndroidComposeRule<HiltComponentActivity>()` (from `:ui-test-hilt-manifest`) for Hilt-injected screens.
- **Screenshot**: Roborazzi (JVM, free) on `:core:designsystem` and per-feature smoke screen.
- **Coverage gates** (CI): line coverage ≥80% on `:core:*`; ≥85% on `:feature:*` ViewModels; UI tests cover the golden path per feature.

### Theming

Material 3 with dynamic colour on Android 12+, fallback to a hand-tuned palette below that. Theme owned by `:core:designsystem`, exposed as `OpenPtvTheme { content() }`. Borrow ReadYou's `MaterialYouStandard.kt` and palette extraction code outright (Apache 2.0 compatible) so non-dynamic-colour devices feel intentional, not styled-by-accident.

## Backend architecture

### Layout

```go
// cmd/openptvd/main.go — wiring only
func main() { config → server → http.ListenAndServe }

// internal/config — env-var loaded, validated at boot
// internal/ptv — HMAC signer; the only place that touches PTV_KEY
// internal/proxy — http.Handler; takes a *ptv.Signer + cache.Cache
// internal/cache — interface + memory impl; per-route TTL
// internal/observe — slog setup, request middleware, Prometheus metrics
```

### Signing

PTV's HMAC scheme:

```
url     = /v3/stops/1071/route_types/0?devid=DEVID
sig     = uppercase(hex(hmac_sha1(KEY, url)))
final   = /v3/stops/1071/route_types/0?devid=DEVID&signature=SIG
```

Encapsulated in `internal/ptv.Signer` so no other package can leak the key. `Signer` exposes only `Sign(rawPath string) (signedURL string, error)`.

### Cache

In-process LRU keyed by full path+query (sans `signature`). Per-prefix TTL:

| Prefix             | TTL  | Reason                               |
| ------------------ | ---- | ------------------------------------ |
| `/v3/stops/...`    | 1 h  | Stop metadata changes rarely         |
| `/v3/routes/...`   | 1 h  | Route metadata changes rarely        |
| `/v3/route_types`  | 24 h | Effectively static                   |
| `/v3/search/...`   | 10 m | OK to be slightly stale              |
| `/v3/departures/*` | 15 s | Real-time-ish; short TTL is fine     |
| `/v3/disruptions/*`| 1 m  |                                      |
| `/v3/patterns/*`   | 5 m  | Stopping pattern for current trip    |

Cache stores raw bytes plus content type and `Cache-Control` derived from TTL — let Cloudflare layer cache too.

### Observability

`log/slog` with JSON handler in production, text in dev. Per-request:

```json
{"time":"...","level":"INFO","msg":"proxy","path":"/v3/stops/1071","cache":"hit","upstream_status":0,"latency_ms":1.2}
```

`/metrics` exposes Prometheus counters (requests, cache hits, upstream errors) and a latency histogram. `/healthz` for the platform liveness probe.

### Threat model (deferred but documented)

The proxy is dumb on purpose. Risks and mitigations:

| Risk                                           | Mitigation                                                                                              |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Anyone uses the public proxy as a free PTV API | Cloudflare WAF + Turnstile / Workers rate-limit, blocked at edge. Backend never sees attack traffic.    |
| PTV quota exhaustion                           | In-process cache + edge cache. PTV `429` triggers exponential backoff on backend, surfaced as 503.      |
| Key leak                                       | Key only ever in env var of the running binary. Never logged. `Signer` is a sealed type.                |
| Data integrity                                 | Pass-through. We don't transform PTV bodies; cache stores raw bytes.                                    |

## Phase roadmap (summary)

Each phase is a self-contained markdown file in `docs/mobile/` or `docs/backend/`, structured for direct GitHub-issue import. Phases compose; you can ship after any phase.

### Mobile phases

1. **Skeleton + theme** — multi-module Gradle, Hilt, Material You (with ReadYou palette overlay), Navigation 3, empty home destination.
2. **Search** — `:core:network`, `:core:data`, `:feature:search`, ViewModel + Compose; first use of `Result<T>`.
3. **Stop detail + departures** — `:feature:stop-detail`, lifecycle-aware polling.
4. **Favourites** — `:core:database`, `:core:datastore` with typed Preference DSL, `:feature:favourites`, star action, reorder.
5. **Nearby map** — `:feature:nearby` with MapLibre + OpenFreeMap, location permission flow.
6. **Routes** — route detail, lines serving each stop.
7. **Glance widget** — next-departure widget, WorkManager via `SyncInitializer`, `:sync:work` + `:sync:sync-test`.
8. **Disruption notifications** — periodic poll, NotificationCompat + Android-13 perm.
9. **Stopping pattern** — "where does this run go" timeline view.
10. **Disruption browser** — list + filter for active disruptions on followed routes.
11. **Polish** — accessibility (TalkBack labels, large fonts), baseline profiles, macrobenchmarks, custom `:lint` rules.

### Backend phases

1. **Proxy MVP** — HMAC signer, pass-through, health check, slog logging, unit + integration tests.
2. **Caching** — per-prefix TTL, LRU, concurrency-safe.
3. **Observability** — Prometheus metrics, request middleware, pprof gated.
4. **Hardening** — graceful shutdown, X-Forwarded-For trust, retry-on-429 with backoff, Dockerfile + CI.
5. **Deploy + edge** — pick a host (Fly.io recommended), document Cloudflare Workers + Turnstile setup, smoke tests through edge.

### Cross-cutting (not phased)

- **CI** — GitHub Actions workflows are added in Phase 1 for both mobile and backend.
- **ADRs** — significant design changes get an `docs/adr/NNNN-*.md`. The decisions in this document are baseline; they can be revised but should not be silently overridden in code.

## Open questions / future ADRs

- **Hosted-instance posture**: do we operate `api.openptv.app`? If so, who pays the PTV quota and what's the abuse SLA? Or do we ship the proxy and tell users to run their own?
- **F-Droid distribution**: defer until after MVP. Glance widget + WorkManager are F-Droid-clean; FCM is not (we're not using FCM).
- **Crash reporting**: Sentry self-hosted? Acra? Defer to Phase 11.
- **i18n**: English-only at first. Add `values-*` directories when there's demand.
- **Coil 2 → 3**: revisit when both reference apps move; not worth being ahead alone.
