# Mobile Phase 5 — Nearby stops map (MapLibre + OpenFreeMap)

> Goal: a map screen showing OSM tiles, the user's location, and stop pins for everything within walking distance. Tap a pin → stop detail.

**Depends on:** Mobile Phase 3 (stop detail destination), Phase 4 (so the favourites "nearest" sort is no longer disabled).
**Blocks:** none.

## Scope

Introduce the map dependency for the first time. Choose a MapLibre Compose binding, integrate it cleanly behind a thin `:feature:nearby` boundary, and handle the location permission flow without surprising the user.

## Deliverables

### Library choice
- [ ] Add `org.maplibre.compose:maplibre-compose` (BSD-3) as the only map dependency.
- [ ] Pin OpenFreeMap style URL: `https://tiles.openfreemap.org/styles/positron` (light) and `dark` (auto-pick from theme).
- [ ] No tile API key. Document fallback (MapTiler / Stadia) in `:feature:nearby/README.md` for self-hosters.

### `:core:domain` additions
- [ ] `Coordinates(lat, lng)`, `Bounds`.
- [ ] `NearbyStopsRepository.stopsNear(coordinates, radiusMeters): Result<List<Stop>>`.

### `:core:network` additions
- [ ] `BackendApiService.stopsNearLocation(latLng, maxDistance)`.

### Location
- [ ] `:core:common` adds `LocationProvider` interface; impl in `:core:data` wraps `FusedLocationProviderClient` (Play services-free fallback: `LocationManager` GPS + Network).
- [ ] Permission flow uses Accompanist-free `rememberLauncherForActivityResult`. Ask first only when user lands on the map; explain the rationale via a Material 3 dialog before requesting.
- [ ] Don't request background location.

### `:feature:nearby`
- [ ] `NearbyScreen` with a `MapLibreMap` composable.
- [ ] User-location dot, follow-me FAB.
- [ ] Stop pins clustered above zoom 14, individual below.
- [ ] Tap pin → bottom sheet with name, mode icon, "View stop" → stop-detail.
- [ ] Initial camera: user location if granted, else Melbourne CBD (-37.8136, 144.9631) zoom 12.
- [ ] Re-fetch stops when camera idles; debounce ≥500 ms; use a small overscan beyond viewport so panning feels alive.
- [ ] Empty state for "no stops in this area" (regional Victoria).
- [ ] Permission-denied state with a button to system settings.

## Out of scope

- Offline tiles. Phase 11 if demand exists.
- Route shapes overlaid on map. Phase 6.
- Real-time vehicle dots. Not in PTV API v3.

## Acceptance criteria

- First entry without location permission shows a clear rationale dialog before the system prompt.
- Denying permission shows a non-blocking explanation and still loads the CBD view.
- Granting permission centres the camera on the user within 5 s on a real device.
- Panning across central Melbourne shows pins continuously; clusters break apart when zooming in.
- Tapping a pin opens the bottom sheet within 1 frame; "View stop" navigates and the back button returns to the map at the same position.
- 60 fps on a Pixel 6 idle pan; verified via in-app `androidx.tracing` trace dump in debug builds.

## Test plan

- `:core:data`
  - `NearbyStopsRepositoryImplTest` with MockWebServer: success, empty, error.
  - `LocationProviderImplTest` with Robolectric for the system path.
- `:feature:nearby`
  - ViewModel test: permission granted vs denied state machines; camera-idle debouncing of fetch.
  - Compose UI: pin tap opens sheet with correct stop name (the MapLibre composable mocked behind a `MapView` interface).
  - Roborazzi: permission rationale, denied state, sheet expanded.
- Manual: indoor location lock with mocked GPS (`adb emu geo fix`) at Flinders Street; pan to Federation Square; pan to Coburg.

## Implementation notes

- Wrap the MapLibre composable in your own `OpenPtvMap` that accepts only domain types. Tests inject a fake.
- Camera-idle listener is a flow: `callbackFlow { addOnCameraIdleListener { trySend(camera) } }`.
- `ACCESS_COARSE_LOCATION` is enough for stop discovery; don't ask for fine. PTV stops are not block-precise.
- OpenFreeMap rate-limits anonymous requests; cache style JSON for 24 h via OkHttp's HTTP cache (`Cache(File("$cacheDir/maps"), 50 * 1024 * 1024)`).
- F-Droid considerations: FusedLocationProviderClient is Google Play Services. If F-Droid distribution lands later, abstract `LocationProvider` so a Play-Services-free flavour can swap in `LocationManager` only.

## References

- [MapLibre Compose](https://github.com/maplibre/maplibre-compose)
- [OpenFreeMap](https://openfreemap.org)
- PTV API: `GET /v3/stops/location/{latitude},{longitude}`
