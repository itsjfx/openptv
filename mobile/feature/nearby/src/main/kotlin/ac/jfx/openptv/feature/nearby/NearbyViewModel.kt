package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.DeviceHeadingProvider
import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.data.NearbyStopsRepository
import ac.jfx.openptv.core.data.StopDetailRepository
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the nearby map screen. Owns the camera position, the pin list, the permission
 * state, the follow-me toggle, the route-type filter, and the pin-tap bottom sheet (including
 * its on-demand routes + 30 s realtime departures fetches).
 *
 * **State machine.**
 *  - On entry → [NearbyUiState.PermissionUnasked]. The screen asks the user for coarse location.
 *  - Granted → snapshot `LocationProvider.lastKnown()`, centre on that (or Melbourne CBD if no
 *    fix yet); transition to [NearbyUiState.Loaded] and begin the camera-idle fetch loop.
 *  - Denied → [NearbyUiState.PermissionDenied] with the CBD camera + pins for the CBD area;
 *    user can still pan and use the map.
 *
 * **Camera-idle debounce.** Pin fetches are gated on a [MutableSharedFlow] of camera positions,
 * debounced ≥ 500 ms via `Flow.debounce`. The debounce satisfies acceptance criterion "panning
 * across central Melbourne shows pins continuously" — without it, every micro-pan would fire a
 * fetch and the OpenFreeMap rate-limit-on-tiles + PTV-proxy rate-limit-on-stops would bite.
 *
 * **Overscan.** The fetch radius is derived from the visible zoom: roughly `RADIUS_BASE_METERS *
 * 2^(MAX_ZOOM - zoom)` capped at [MAX_RADIUS_METERS]. The base is sized so the visible viewport
 * fits inside the fetched circle with ~30% headroom — small enough that panning a screen-width
 * triggers a refresh, big enough that the user sees pins as they enter the visible area.
 *
 * **Route-type filter.** [NearbyUiState.routeTypeFilter] is the single source of truth — both
 * the map pins and the bottom-sheet `:feature:nearby` (#80) subscribe to it. Toggling a chip
 * updates the filter and immediately re-fires the camera-idle fetch with the new filter
 * forwarded to PTV's `route_types` query parameter (server-side filter — PTV's response is
 * already small for the typical viewport, but server-side keeps the bytes-on-wire minimal in
 * dense regions like Flinders Street where a "trams only" tap should drop ~80% of the payload).
 *
 * **Bottom-sheet fetches.** Tapping a pin opens the sheet immediately with a placeholder
 * (`routes = null`, `departures = null`); the routes one-shot + departures Flow fan out from
 * [pinTapped]. Both jobs are tracked in [sheetJob] so a tap on a different pin (or sheet
 * dismissal) cancels the previous in-flight fetches — the sheet never shows stale rows for the
 * wrong stop.
 */
@HiltViewModel
class NearbyViewModel
    @Inject
    constructor(
        private val locationProvider: LocationProvider,
        private val deviceHeadingProvider: DeviceHeadingProvider,
        private val nearbyStopsRepository: NearbyStopsRepository,
        private val stopDetailRepository: StopDetailRepository,
        private val departureRepository: DepartureRepository,
    ) : ViewModel() {
        private val _uiState: MutableStateFlow<NearbyUiState> =
            MutableStateFlow(NearbyUiState.PermissionUnasked)
        val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

        /**
         * Inputs to the debounced fetch pipeline. `replay = 1` so an emit that fires before the
         * collector in [wireCameraDebounce] is scheduled (e.g. the initial seed inside
         * `onPermissionResult` while we're still under a `StandardTestDispatcher`) is still
         * delivered — the collector picks it up as a replay value as soon as it starts.
         *
         * The pair carries `(camera, filter)` so the collector debounces both the camera and
         * the filter through the same gate. A filter toggle re-emits the current camera so the
         * fetch fires with the new filter without waiting for the next pan.
         */
        private val pinFetchTriggers: MutableSharedFlow<PinFetchTrigger> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 1)

        /**
         * Holds the bottom-sheet's in-flight fetches (routes + departures) so a tap on a new
         * pin can cancel the previous pin's work. `null` when no sheet is open.
         */
        private var sheetJob: Job? = null

        /**
         * Job for the continuous user-location subscription (issue #99). Started on permission
         * grant — feeds `userLocation` updates as the user moves so the blue dot follows them.
         * Cancelled / restarted if permission flips. Separate Job from [sheetJob] so a bottom-
         * sheet dismissal doesn't tear down the dot tracking.
         */
        private var userLocationJob: Job? = null

        /**
         * Job for the continuous compass / heading subscription (issue #99). Same pattern as
         * [userLocationJob] — starts on permission grant, completes cleanly if the device has no
         * rotation sensor (the screen then just renders the dot with no cone).
         */
        private var userBearingJob: Job? = null

        init {
            wireCameraDebounce()
        }

        /**
         * Caller (the screen) tells us the permission decision. Permission grant kicks off a
         * `lastKnown()` snapshot and centres the camera; denial keeps the CBD view and lets the
         * user pan.
         */
        fun onPermissionResult(granted: Boolean) {
            viewModelScope.launch {
                if (granted) {
                    val fix = locationProvider.lastKnown()
                    val initialCamera =
                        OpenPtvCameraState(
                            centre = fix ?: MELBOURNE_CBD,
                            zoom = INITIAL_ZOOM,
                        )
                    _uiState.value =
                        NearbyUiState.Loaded(
                            camera = initialCamera,
                            pins = emptyList(),
                            userLocation = fix,
                            userBearing = null,
                            isFollowingUser = fix != null,
                            pendingSheet = SheetState.Closed,
                            showEmptyHint = false,
                            routeTypeFilter = currentFilter(),
                        )
                    // Seed the debounce so the initial fetch lands without a manual pan.
                    pinFetchTriggers.tryEmit(PinFetchTrigger(initialCamera, currentFilter()))
                    // Start tracking the user's location + heading for the blue-dot indicator
                    // (issue #99). Both subscriptions are best-effort: a permission revocation or
                    // missing sensor completes the flow cleanly, leaving `userLocation`/
                    // `userBearing` at their last value (the dot freezes rather than vanishing,
                    // which matches what every other map app does when GPS drops momentarily).
                    startUserLocationTracking()
                    startUserBearingTracking()
                } else {
                    _uiState.value =
                        NearbyUiState.PermissionDenied(
                            camera = OpenPtvCameraState(centre = MELBOURNE_CBD, zoom = INITIAL_ZOOM),
                            pins = emptyList(),
                            routeTypeFilter = currentFilter(),
                        )
                    pinFetchTriggers.tryEmit(
                        PinFetchTrigger(
                            OpenPtvCameraState(MELBOURNE_CBD, INITIAL_ZOOM),
                            currentFilter(),
                        ),
                    )
                    // Permission denied — make sure neither tracker is still running from a prior
                    // grant within the same VM lifetime.
                    userLocationJob?.cancel()
                    userLocationJob = null
                    userBearingJob?.cancel()
                    userBearingJob = null
                }
            }
        }

        /**
         * Subscribe to [LocationProvider.observe] and push every new fix into the [Loaded] state's
         * `userLocation` field. Auto-cancels the previous subscription if called twice — used by
         * [onPermissionResult] to restart cleanly after a permission flip.
         *
         * Follow-me is preserved when the user is currently following: the camera also re-centres
         * on the new fix so the dot stays under the crosshair. A user who has panned away
         * (`isFollowingUser = false`) just sees the dot move; the camera doesn't chase them.
         */
        private fun startUserLocationTracking() {
            userLocationJob?.cancel()
            userLocationJob =
                viewModelScope.launch {
                    locationProvider.observe().collect { fix ->
                        val current = _uiState.value as? NearbyUiState.Loaded ?: return@collect
                        val nextCamera =
                            if (current.isFollowingUser) {
                                current.camera.copy(centre = fix)
                            } else {
                                current.camera
                            }
                        _uiState.value =
                            current.copy(userLocation = fix, camera = nextCamera)
                    }
                }
        }

        /**
         * Subscribe to [DeviceHeadingProvider.observe] and push every new bearing into the
         * [Loaded] state's `userBearing` field. Same lifecycle / restart pattern as
         * [startUserLocationTracking]. Completion (no rotation sensor) leaves `userBearing` at
         * `null`, which the map renders as "no cone" — issue #99's compass-less device path.
         */
        private fun startUserBearingTracking() {
            userBearingJob?.cancel()
            userBearingJob =
                viewModelScope.launch {
                    deviceHeadingProvider.observe().collect { bearing ->
                        val current = _uiState.value as? NearbyUiState.Loaded ?: return@collect
                        _uiState.value = current.copy(userBearing = bearing)
                    }
                }
        }

        /** Called from MapLibre's camera-idle listener through the [OpenPtvMap] callback. */
        fun onCameraIdle(camera: OpenPtvCameraState) {
            val current = _uiState.value
            when (current) {
                is NearbyUiState.Loaded -> {
                    // A manual pan disengages follow-me — same convention every other map app uses.
                    val stillFollowing =
                        current.isFollowingUser &&
                            current.userLocation != null &&
                            camera.centre.distanceTo(current.userLocation) < FOLLOW_LEASH_METERS
                    _uiState.value = current.copy(camera = camera, isFollowingUser = stillFollowing)
                }
                is NearbyUiState.PermissionDenied ->
                    _uiState.value = current.copy(camera = camera)
                NearbyUiState.PermissionUnasked -> Unit
            }
            pinFetchTriggers.tryEmit(PinFetchTrigger(camera, currentFilter()))
        }

        /**
         * Toggle a [RouteType] chip on/off. The filter is the single source of truth for both
         * the map pins and the bottom-sheet list (issue #80) — adding a second knob would just
         * create drift between the two surfaces.
         *
         * **Invariant: the filter is never empty.** A tap that would deselect the only selected
         * chip is a no-op (returns early without re-firing the fetch). An empty filter would
         * render zero stops everywhere — a dead-end UX — so the chip strip can always offer the
         * user at least one mode worth of pins.
         *
         * Filter changes immediately re-fire the pin fetch with the new filter set. A debounce
         * still applies, so a user who taps three chips in rapid succession only fires one
         * request. The previous in-flight fetch (under `collectLatest`) is cancelled.
         *
         * Reads the filter directly from `_uiState.value` (rather than a captured local) so the
         * "no-op when only one selected" check is consistent with the latest state — protects
         * against a stale-snapshot race if two toggles fire on the same dispatcher tick.
         */
        fun onRouteTypeFilterToggled(routeType: RouteType) {
            // Unknown isn't a chip — guarding here keeps the UI from accidentally producing a
            // filter set that the repository would have to drop anyway.
            if (routeType == RouteType.Unknown) return
            val current = currentFilter()
            val nextFilter =
                if (current.contains(routeType)) {
                    // Trying to deselect the only selected chip — no-op (invariant: never empty).
                    if (current.size <= 1) return
                    current - routeType
                } else {
                    current + routeType
                }
            // Already at the desired state (e.g. defensive: the toggle produced the same set
            // because the routeType wasn't really in/out). Skip the re-emit so the debounce
            // pipeline doesn't fire a redundant fetch.
            if (nextFilter == current) return
            updateFilter(nextFilter)
            currentCamera()?.let { camera ->
                pinFetchTriggers.tryEmit(PinFetchTrigger(camera, nextFilter))
            }
        }

        /** Called when the user taps a pin. */
        fun onPinClicked(stop: Stop) {
            val current = _uiState.value as? NearbyUiState.Loaded ?: return
            // Render the sheet in its loading shape immediately so the user gets feedback on
            // tap; the routes + departures land asynchronously.
            _uiState.value =
                current.copy(
                    pendingSheet = SheetState.Open(StopBottomSheet(stop = stop)),
                )
            startSheetFetches(stop)
        }

        /** Called when the bottom sheet is dismissed without selecting "View stop". */
        fun onSheetDismissed() {
            sheetJob?.cancel()
            sheetJob = null
            val current = _uiState.value
            if (current is NearbyUiState.Loaded) {
                _uiState.value = current.copy(pendingSheet = SheetState.Closed)
            }
        }

        /** Re-centre the camera on the user's last known fix. */
        fun onFollowMeClicked() {
            val current = _uiState.value as? NearbyUiState.Loaded ?: return
            val fix = current.userLocation ?: return
            _uiState.value =
                current.copy(
                    camera = OpenPtvCameraState(centre = fix, zoom = FOLLOW_ME_ZOOM),
                    isFollowingUser = true,
                )
        }

        @OptIn(FlowPreview::class)
        private fun wireCameraDebounce() {
            viewModelScope.launch {
                pinFetchTriggers
                    .debounce(CAMERA_IDLE_DEBOUNCE_MS)
                    // `collectLatest` cancels an in-flight fetch if the user pans (or toggles a
                    // filter) again mid-call. Avoids a slow fetch landing on top of a fresher
                    // one and overwriting the pins the user is currently looking at.
                    .collectLatest { trigger ->
                        val radius = radiusForZoom(trigger.camera.zoom)
                        val result =
                            nearbyStopsRepository.stopsNear(
                                coordinates = trigger.camera.centre,
                                radiusMeters = radius,
                                routeTypes = trigger.filter,
                            )
                        val pins =
                            when (result) {
                                is Result.Success -> result.data
                                is Result.Error,
                                Result.Loading,
                                -> emptyList()
                            }
                        when (val current = _uiState.value) {
                            is NearbyUiState.Loaded ->
                                _uiState.value =
                                    current.copy(
                                        pins = pins,
                                        showEmptyHint = result is Result.Success && pins.isEmpty(),
                                    )
                            is NearbyUiState.PermissionDenied ->
                                _uiState.value = current.copy(pins = pins)
                            NearbyUiState.PermissionUnasked -> Unit
                        }
                    }
            }
        }

        /**
         * Kicks off the bottom-sheet's routes + departures fetches in a single Job so a sheet
         * dismissal (or a tap on a new pin) cancels both via [sheetJob].`cancel()`. Both update
         * `pendingSheet` in place by copying the existing [StopBottomSheet] so a slow-routes /
         * fast-departures (or vice-versa) lands cleanly without one fetch overwriting the
         * other's data.
         *
         * The departures fetch goes through [DepartureRepository.observeDepartures] so the 30 s
         * polling tick is reused — every other realtime surface (stop detail, the favourites
         * grouping) ticks on the same cadence, so the user sees consistent freshness.
         */
        private fun startSheetFetches(stop: Stop) {
            sheetJob?.cancel()
            sheetJob =
                viewModelScope.launch {
                    val outerJob = this
                    // Routes one-shot
                    launch {
                        val result = stopDetailRepository.getStopDetail(stop.id, stop.routeType)
                        updateSheet(forStopId = stop.id.value) { current ->
                            when (result) {
                                is Result.Success -> current.copy(routes = result.data.servingRoutes)
                                is Result.Error -> current.copy(hadError = true)
                                Result.Loading -> current
                            }
                        }
                    }
                    // Departures polling
                    launch {
                        departureRepository
                            .observeDepartures(stop.id, stop.routeType)
                            .collect { result ->
                                updateSheet(forStopId = stop.id.value) { current ->
                                    when (result) {
                                        is Result.Success ->
                                            current.copy(
                                                departures =
                                                    result.data
                                                        .sortedBy { it.estimatedDepartureUtc ?: it.scheduledDepartureUtc }
                                                        .take(StopBottomSheet.DEPARTURES_PREVIEW_LIMIT),
                                            )
                                        is Result.Error ->
                                            // Keep whatever we already have on screen; flip the
                                            // error chip on. The next 30 s tick can still recover.
                                            current.copy(hadError = true)
                                        Result.Loading -> current
                                    }
                                }
                            }
                    }
                    @Suppress("UNUSED_EXPRESSION")
                    outerJob
                }
        }

        /**
         * Atomic update of the currently-open sheet, gated on `forStopId` matching the open
         * sheet's stop id — guards against a fetch landing for a stop the user has already
         * dismissed (the cancellation usually wins this race, but the gate is cheap insurance).
         */
        private fun updateSheet(
            forStopId: Int,
            transform: (StopBottomSheet) -> StopBottomSheet,
        ) {
            val current = _uiState.value as? NearbyUiState.Loaded ?: return
            val sheet = current.pendingSheet as? SheetState.Open ?: return
            if (sheet.sheet.stop.id.value != forStopId) return
            _uiState.value = current.copy(pendingSheet = SheetState.Open(transform(sheet.sheet)))
        }

        private fun currentFilter(): Set<RouteType> = _uiState.value.routeTypeFilter

        private fun currentCamera(): OpenPtvCameraState? =
            when (val state = _uiState.value) {
                is NearbyUiState.Loaded -> state.camera
                is NearbyUiState.PermissionDenied -> state.camera
                NearbyUiState.PermissionUnasked -> null
            }

        private fun updateFilter(filter: Set<RouteType>) {
            when (val state = _uiState.value) {
                is NearbyUiState.Loaded -> _uiState.value = state.copy(routeTypeFilter = filter)
                is NearbyUiState.PermissionDenied -> _uiState.value = state.copy(routeTypeFilter = filter)
                NearbyUiState.PermissionUnasked -> Unit
            }
        }

        /**
         * Picks a fetch radius from the current zoom. The mapping is a coarse power-of-two: each
         * zoom step out doubles the radius, up to a per-request ceiling so a low-zoom pan over
         * regional Victoria doesn't ask PTV for an unreasonable bbox.
         */
        private fun radiusForZoom(zoom: Double): Int {
            val zoomDelta = (MAX_ZOOM - zoom).coerceAtLeast(0.0)
            // 2^zoomDelta — use Math.pow + cast; we cap at MAX_RADIUS_METERS anyway.
            val multiplier = Math.pow(2.0, zoomDelta)
            val radius = (RADIUS_BASE_METERS * multiplier).toInt()
            return radius.coerceIn(RADIUS_BASE_METERS, MAX_RADIUS_METERS)
        }

        private data class PinFetchTrigger(
            val camera: OpenPtvCameraState,
            val filter: Set<RouteType>,
        )

        internal companion object {
            /** Melbourne CBD centroid — Flinders Street area. Default camera when no fix. */
            internal val MELBOURNE_CBD: Coordinates = Coordinates(lat = -37.8136, lng = 144.9631)

            /**
             * Initial zoom on entry. Picked to be > the MapLibre cluster max-zoom (14) so the
             * user lands on the unclustered "individual stops" view as soon as permission is
             * granted — at zoom 12 every CBD pin would collapse into one cluster and the user
             * would need to manually zoom in to see anything useful.
             */
            internal const val INITIAL_ZOOM: Double = 15.0

            /** Follow-me FAB re-centres at a tighter zoom (~street level). */
            internal const val FOLLOW_ME_ZOOM: Double = 15.0

            /** Camera-idle debounce, per the issue's acceptance criterion. */
            internal const val CAMERA_IDLE_DEBOUNCE_MS: Long = 500L

            /** Base radius at MAX_ZOOM — the visible viewport diameter at street level is ~500 m. */
            internal const val RADIUS_BASE_METERS: Int = 500

            /** Cap per-fetch radius. PTV rejects unreasonable bboxes; we never exceed this. */
            internal const val MAX_RADIUS_METERS: Int = 5_000

            /** Zoom at which RADIUS_BASE_METERS is the right radius. */
            internal const val MAX_ZOOM: Double = 15.0

            /**
             * Inside this many metres, the camera is "still on the user" and follow-me stays on.
             * Outside, a manual pan disengages it. Picked to be larger than a finger-jitter
             * camera-idle event but smaller than a deliberate pan.
             */
            internal const val FOLLOW_LEASH_METERS: Double = 50.0
        }
    }
