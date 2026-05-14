# `:feature:nearby`

Phase 05 map screen — MapLibre Android (BSD-2-Clause) + OpenFreeMap tiles.

## Library

- `org.maplibre.gl:android-sdk` — the raw MapLibre Android binding. We wrap its `MapView` inside
  `OpenPtvMap` via `AndroidView` so the screen only ever sees domain types
  (`Coordinates`, `Stop`, `OpenPtvCameraState`). Tests inject a `FakeOpenPtvMap` via the
  `OpenPtvMapFactory` Hilt seam — no MapLibre / OpenGL boot needed for ViewModel + UI tests.
- The issue + `docs/mobile/phase-05-nearby-map.md` named `org.maplibre.compose:maplibre-compose`,
  which doesn't exist as a published artifact today. The alternatives are
  `org.ramani-maps:ramani-maplibre` (MPL-2.0 community wrapper) and the raw SDK above; we picked
  the raw SDK because the wrap is mandatory anyway for the test seam.

## Tiles

- Default style: `https://tiles.openfreemap.org/styles/positron` (light) /
  `https://tiles.openfreemap.org/styles/dark` (dark theme).
- No API key. OpenFreeMap is anonymous + free for typical app traffic.
- Style JSON + glyph + sprite + tile fetches are routed through a dedicated 50 MiB OkHttp
  `Cache` (see `MapsCacheModule`), scoped via Hilt's `@MapsCache` qualifier so the regular API
  `OkHttpClient` stays untouched. The cache reduces hits against OpenFreeMap's anonymous tier.

## Self-host / fallback options

If OpenFreeMap ever rate-limits us or goes offline, swap the style URL constant in
`NearbyTileStyle.kt` to one of:

- [MapTiler Cloud](https://www.maptiler.com/cloud/) — paid, has a generous free tier; needs an API
  key inserted into the style URL.
- [Stadia Maps](https://stadiamaps.com/) — paid, free for non-commercial use; also key-bearing.
- Self-host with [Planetiler](https://github.com/onthegomap/planetiler) + an `nginx` tile server
  on your own backend. The Go proxy could even forward `/styles/positron` requests onward.

The wrapper layer means none of the rest of the codebase changes when the style source does — the
URL is read at composition time from `NearbyTileStyle.styleUrl(themeMode)`.

## F-Droid future-proofing

MapLibre Android (`org.maplibre.gl:android-sdk`) is BSD-2-Clause and ships with no GMS deps, which
means it's F-Droid-publishable as-is. The `:app:dependencyGuard` baseline locks the transitive
classpath to verify this in CI.
