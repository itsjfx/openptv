package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.DeviceHeadingProvider
import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.data.NearbyStopsRepository
import ac.jfx.openptv.core.data.StopDetailRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.MapRouteTypeFilterPreference
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * **Camera-idle debounce + move-started cancel (issue #109).** Every [onCameraIdle] cancels the
 * previous [fetchJob] and starts a new one that `delay`s [CAMERA_IDLE_DEBOUNCE_MS] before hitting
 * [NearbyStopsRepository] — so a burst of idles inside the window collapses to a single fetch. A
 * [onCameraMoveStarted] from MapLibre cancels the in-flight job outright, so the user dragging
 * across the map doesn't keep burning bandwidth + the PTV rate limit on viewports they're panning
 * past. With #108's LRU cache the previously-fetched pins stay on screen during the drag, so the
 * cancel feels invisible rather than a flicker.
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
        private val userPreferences: UserPreferencesDataStore,
    ) : ViewModel() {
        private val _uiState: MutableStateFlow<NearbyUiState> =
            MutableStateFlow(NearbyUiState.PermissionUnasked)
        val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

        /**
         * Persisted route-type chip selection (issue #112). Loaded once at init eagerly so the
         * first `Loaded` / `PermissionDenied` state can seed [NearbyUiState.routeTypeFilter] from
         * the user's previous session instead of [DEFAULT_FILTER]. A `Deferred` (not a `Flow`)
         * because DataStore only needs to read the value once at startup — subsequent writes are
         * driven by [onRouteTypeFilterToggled] and the in-memory state is the source of truth.
         *
         * Starts eagerly ([CoroutineStart.DEFAULT]) so the DataStore read is already in flight by
         * the time [onPermissionResult] awaits the value. If the persisted set is empty / unknown
         * the parser falls back to [MapRouteTypeFilterPreference.default] — the "filter is never
         * empty" invariant holds at the seed too.
         */
        private val persistedFilter: Deferred<Set<RouteType>> =
            viewModelScope.async(start = CoroutineStart.DEFAULT) {
                userPreferences.mapRouteTypeFilter.first().value
            }

        /**
         * Holds the current pin-fetch coroutine (debounce + network call). Replaced on every
         * camera-idle / filter-toggle; cancelled outright on a camera-move-started so a slow
         * drag doesn't keep a stale fetch in flight. `null` between settle events.
         */
        private var fetchJob: Job? = null

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

        /**
         * One-shot focus coordinate (issue #123) buffered when the user navigates in from
         * stop-detail's "show on map" before the permission flow has resolved. The screen calls
         * [focusOn] inside a `LaunchedEffect` keyed on the focus pair as soon as it composes — at
         * that moment the state is typically [NearbyUiState.PermissionUnasked], so the camera
         * can't be moved yet. We store the coordinate here and consume it on the first transition
         * into [NearbyUiState.Loaded] / [NearbyUiState.PermissionDenied] so the user lands framed
         * on the requested stop instead of their own location.
         *
         * Cleared as soon as it's applied; subsequent permission flips don't re-focus.
         */
        private var pendingFocus: Coordinates? = null

        /**
         * Expected centre of the next [onCameraIdle] after a programmatic camera write (issue
         * #123 / PR #139 follow-up). The "already-granted on arrival + show on map" path races
         * MapLibre's async style-load: the focus camera lands in VM state before the map view
         * has animated to it, so the first `onCameraIdle` MapLibre fires carries the pre-focus
         * frame (user-location / CBD) and would clobber the focus camera back to the stale value
         * — the user ends up looking at their own location instead of the requested stop.
         *
         * We stash the focus coord here whenever we move the camera programmatically; the next
         * [onCameraIdle] is dropped unless its centre is within [PROGRAMMATIC_IDLE_TOLERANCE_METERS]
         * of the expected coord. The first idle that lands on the expected coord clears the slot
         * and normal idle handling resumes — so the suppression is one-shot and a subsequent
         * user-driven pan is honoured as usual.
         */
        private var pendingProgrammaticCenter: Coordinates? = null

        /**
         * LRU cache of every stop the user has fetched this session. Solves the disappear/reappear
         * problem: panning into a region we've fetched before keeps those pins on-screen
         * immediately, instead of dropping them until the next fetch lands.
         *
         * Bounded at [MAX_CACHED_STOPS] so an hour of panning across Melbourne can't blow out
         * memory — a couple of thousand `Stop` records is trivial (each is a handful of strings +
         * two doubles), but the bound is the principled stop-gap.
         *
         * **Filter-aware via clear-on-change.** A route-type chip toggle clears the cache (see
         * [onRouteTypeFilterToggled]) so a "trams only" tap doesn't keep bus pins on the map from
         * a previous fetch. Re-keying by `(stopId, filter)` would let the cache hold stale-filter
         * pins for nothing — the user has just expressed they don't want them. Clearing is
         * simpler and matches user intent.
         */
        private val stopCache = LruStopCache(MAX_CACHED_STOPS)

        /**
         * Caller (the screen) tells us the permission decision. Permission grant kicks off a
         * `lastKnown()` snapshot and centres the camera; denial keeps the CBD view and lets the
         * user pan.
         */
        fun onPermissionResult(granted: Boolean) {
            viewModelScope.launch {
                // Wait for the persisted chip selection (issue #112). The `async` started at init
                // so this `await` is non-blocking once DataStore's first emission has landed —
                // typically already done by the time the screen has finished its permission
                // round-trip. If the persisted load is somehow still pending, we yield until it
                // resolves; using the DEFAULT_FILTER here as a fall-back would silently lose the
                // user's previous "trams only" choice on a slow first launch.
                val seedFilter = persistedFilter.await()
                if (granted) {
                    val fix = locationProvider.lastKnown()
                    // Issue #123: if the user navigated in from stop-detail's "show on map" while
                    // the permission overlay was still up, [focusOn] will have stashed the focus
                    // coordinate. Consume it here so the very first Loaded state lands framed on
                    // the requested stop — otherwise we'd render the user-location camera first
                    // and the screen-side LaunchedEffect wouldn't re-fire (it's keyed on the
                    // focus pair, not on the state transition).
                    val focus = pendingFocus
                    pendingFocus = null
                    pendingProgrammaticCenter = focus
                    val initialCamera =
                        if (focus != null) {
                            OpenPtvCameraState(centre = focus, zoom = FOCUS_ZOOM)
                        } else {
                            OpenPtvCameraState(
                                centre = fix ?: MELBOURNE_CBD,
                                zoom = INITIAL_ZOOM,
                            )
                        }
                    _uiState.value =
                        NearbyUiState.Loaded(
                            camera = initialCamera,
                            pins = emptyList(),
                            userLocation = fix,
                            userBearing = null,
                            // A pending focus disengages follow-me — the user has named a specific
                            // stop, not asked to chase their own location. Same semantics as a
                            // direct [focusOn] call into an already-Loaded state.
                            isFollowingUser = focus == null && fix != null,
                            pendingSheet = SheetState.Closed,
                            showEmptyHint = false,
                            routeTypeFilter = seedFilter,
                        )
                    // Seed the debounce so the initial fetch lands without a manual pan.
                    scheduleFetch(initialCamera, seedFilter)
                    // Start tracking the user's location + heading for the blue-dot indicator
                    // (issue #99). Both subscriptions are best-effort: a permission revocation or
                    // missing sensor completes the flow cleanly, leaving `userLocation`/
                    // `userBearing` at their last value (the dot freezes rather than vanishing,
                    // which matches what every other map app does when GPS drops momentarily).
                    startUserLocationTracking()
                    startUserBearingTracking()
                } else {
                    // Same pending-focus consumption on the denied branch — the user can still see
                    // the map and we honour their "frame on this stop" intent even if location
                    // permission was refused.
                    val focus = pendingFocus
                    pendingFocus = null
                    pendingProgrammaticCenter = focus
                    val initialCamera =
                        if (focus != null) {
                            OpenPtvCameraState(centre = focus, zoom = FOCUS_ZOOM)
                        } else {
                            OpenPtvCameraState(centre = MELBOURNE_CBD, zoom = INITIAL_ZOOM)
                        }
                    _uiState.value =
                        NearbyUiState.PermissionDenied(
                            camera = initialCamera,
                            pins = emptyList(),
                            routeTypeFilter = seedFilter,
                        )
                    scheduleFetch(initialCamera, seedFilter)
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
            val expected = pendingProgrammaticCenter
            if (expected != null) {
                if (camera.centre.distanceTo(expected) > PROGRAMMATIC_IDLE_TOLERANCE_METERS) {
                    // Stale frame from MapLibre's async setup window — drop it so it doesn't
                    // clobber the focus camera back to the pre-animation viewport. Keep the slot
                    // armed; the post-animation idle on the expected coord will clear it.
                    return
                }
                pendingProgrammaticCenter = null
            }
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
            scheduleFetch(camera, currentFilter())
        }

        /**
         * Called from MapLibre's camera-move-started listener through the [OpenPtvMap] callback.
         * Cancels any in-flight pin fetch — issue #109. The debounce delay won't have elapsed yet
         * for a typical drag-start, so the cancel is mostly defensive against the "fast pan,
         * then a long network round-trip lands mid-drag" case. Pins on screen are unaffected
         * because the LRU cache (#108) holds the previously-rendered set.
         */
        fun onCameraMoveStarted() {
            fetchJob?.cancel()
            fetchJob = null
            // A user-driven move (or any non-programmatic gesture) ends the suppression window —
            // whatever idle lands next is the user's pan, not the stale pre-animation frame, so
            // accept it on arrival.
            pendingProgrammaticCenter = null
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
         * request. [scheduleFetch] cancels the previous [fetchJob] so the latest filter wins.
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
            // Filter changed — purge cached stops so the map doesn't keep showing pins from the
            // previous filter (e.g. bus pins still visible after a "trams only" tap). Cheaper
            // than keying the cache by `(stopId, filter)`: the user has explicitly asked for a
            // different set, the previous fetches no longer match user intent. Drop the rendered
            // pin list too so the stale set isn't visible during the debounce window.
            stopCache.clear()
            updateFilter(nextFilter)
            clearRenderedPins()
            // Persist the new selection so the next app launch reopens on the same chip set
            // (issue #112). Fire-and-forget on `viewModelScope` — DataStore serialises the write
            // on its own dispatcher so two rapid taps land in order without explicit locking.
            MapRouteTypeFilterPreference.of(nextFilter).put(viewModelScope, userPreferences.dataStore)
            currentCamera()?.let { camera ->
                scheduleFetch(camera, nextFilter)
            }
        }

        private fun clearRenderedPins() {
            when (val state = _uiState.value) {
                is NearbyUiState.Loaded -> _uiState.value = state.copy(pins = emptyList())
                is NearbyUiState.PermissionDenied -> _uiState.value = state.copy(pins = emptyList())
                NearbyUiState.PermissionUnasked -> Unit
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

        /**
         * One-shot "focus the map on this coordinate" entry point (issue #123). The stop-detail
         * screen's "show on map" affordance calls this with the stop's `(lat, lon)` so the user
         * jumps back to the Nearby surface already framed on the stop they were looking at.
         *
         * Re-centres at [FOCUS_ZOOM] — slightly tighter than the default initial zoom so the user
         * lands on the unclustered "individual stops" view with the focused stop visible. Disengages
         * follow-me: the user has expressed they want to look at a specific spot, not chase their
         * own location.
         *
         * Schedules a fresh pin fetch immediately (rather than waiting for a camera-idle round-trip
         * from the map view) so the focused viewport's stops are visible without a manual pan. The
         * call is debounced through [scheduleFetch] for free — the existing 500 ms window is short
         * enough that the user doesn't notice but cheap insurance against rapid re-entries.
         *
         * The screen calls this once per entry via a `LaunchedEffect` keyed on the focus pair, so
         * a configuration change (rotation, dark-mode flip) doesn't re-fire the focus and reset
         * the camera if the user has since panned away.
         *
         * **PermissionUnasked race fix (PR #139 follow-up).** When the user navigates in from
         * stop-detail's "show on map", the screen's permission-resolve `LaunchedEffect` and the
         * focus `LaunchedEffect` fire on the same dispatcher tick. The focus one usually wins
         * the race — at which point [_uiState] is still [NearbyUiState.PermissionUnasked]. We
         * can't move the camera yet (the overlay is up and there's no Loaded/Denied variant to
         * copy into), but we MUST NOT drop the request: the screen's `LaunchedEffect` is keyed
         * on the focus pair, not on the state, so it doesn't re-fire when Loaded lands. Stash
         * the coordinate in [pendingFocus] and let [onPermissionResult] consume it when the
         * permission flow finishes — that produces the very first Loaded camera at the requested
         * stop, no second hop required.
         */
        fun focusOn(coordinates: Coordinates) {
            val camera = OpenPtvCameraState(centre = coordinates, zoom = FOCUS_ZOOM)
            val filter = currentFilter()
            when (val current = _uiState.value) {
                is NearbyUiState.Loaded ->
                    _uiState.value =
                        current.copy(
                            camera = camera,
                            isFollowingUser = false,
                        )
                is NearbyUiState.PermissionDenied ->
                    _uiState.value = current.copy(camera = camera)
                NearbyUiState.PermissionUnasked -> {
                    // Buffer the request — [onPermissionResult] will consume it. The fetch is
                    // also deferred; without a Loaded state there's nothing to render pins onto
                    // yet, and the consumer will schedule its own fetch with the focused camera.
                    pendingFocus = coordinates
                    return
                }
            }
            pendingProgrammaticCenter = coordinates
            scheduleFetch(camera, filter)
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

        /**
         * Schedule a debounced pin fetch. Cancels the previous [fetchJob] (which either was still
         * in its `delay` window or already mid-network — `collectLatest`-style cancellation either
         * way) and launches a fresh job that waits [CAMERA_IDLE_DEBOUNCE_MS] before calling
         * [NearbyStopsRepository.stopsNear]. A rapid sequence of camera idles therefore collapses
         * to a single fetch (the last one wins), and an [onCameraMoveStarted] from MapLibre can
         * cancel the in-flight job outright via [fetchJob].`cancel()`.
         */
        private fun scheduleFetch(
            camera: OpenPtvCameraState,
            filter: Set<RouteType>,
        ) {
            fetchJob?.cancel()
            fetchJob =
                viewModelScope.launch {
                    delay(CAMERA_IDLE_DEBOUNCE_MS)
                    val radius = radiusForZoom(camera.zoom)
                    val result =
                        nearbyStopsRepository.stopsNear(
                            coordinates = camera.centre,
                            radiusMeters = radius,
                            routeTypes = filter,
                        )
                    // Fold the fresh stops into the LRU cache and render the merged set.
                    // The user sees previously-fetched pins persist as they pan back into a
                    // region; the fresh fetch refreshes data for the current viewport.
                    // We keep every cached stop in `pins` (no viewport bbox filter) — MapLibre
                    // clusters them and the per-pin overhead is negligible at the 2000-stop
                    // bound, so the extra complexity of a bbox filter isn't worth it.
                    val fresh =
                        when (result) {
                            is Result.Success -> result.data
                            is Result.Error,
                            Result.Loading,
                            -> emptyList()
                        }
                    stopCache.putAll(fresh)
                    val pins = stopCache.snapshot()
                    when (val current = _uiState.value) {
                        is NearbyUiState.Loaded ->
                            _uiState.value =
                                current.copy(
                                    pins = pins,
                                    // The hint fires when the fetch succeeded AND the cache is
                                    // still empty after merging — i.e. the user has never seen
                                    // a stop in this session. Once any stop is cached, the
                                    // hint stays off because the map isn't empty anymore.
                                    showEmptyHint = result is Result.Success && pins.isEmpty(),
                                )
                        is NearbyUiState.PermissionDenied ->
                            _uiState.value = current.copy(pins = pins)
                        NearbyUiState.PermissionUnasked -> Unit
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

            /**
             * Zoom for the issue #123 "show stop on map" entry. One step tighter than the initial
             * zoom — the user has named a specific stop, so we frame closer to it than the broad
             * "where am I?" entry zoom.
             */
            internal const val FOCUS_ZOOM: Double = 16.0

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

            /**
             * Tolerance for matching an `onCameraIdle` against [pendingProgrammaticCenter]
             * (issue #123 / PR #139 follow-up). MapLibre's animate-to settles to within a few
             * metres of the requested centre; a stale pre-animation frame is hundreds of metres
             * to kilometres away. Picked tight enough to reject the stale frame and loose enough
             * to absorb settling jitter.
             */
            internal const val PROGRAMMATIC_IDLE_TOLERANCE_METERS: Double = 25.0

            /**
             * Cap on the LRU stop cache. Sized so an hour of pan/zoom across central Melbourne
             * wouldn't evict — a single screen-width of dense CBD fetches a few hundred stops,
             * a typical pan accumulates well under this number. Far below memory pressure: each
             * `Stop` is a handful of strings + two doubles (~150 bytes), so 2000 entries is
             * ~300 KB.
             */
            internal const val MAX_CACHED_STOPS: Int = 2000
        }
    }

/**
 * Tiny insertion-order LRU keyed by [StopId]. A re-insert (i.e. a stop returned by a fresh fetch
 * we've already seen) bumps its recency by removing-then-re-adding under the same key. Eviction
 * fires synchronously on [putAll] / [put] once the size exceeds the bound.
 *
 * Not thread-safe — only touched from the ViewModel's coroutine scope (single Dispatcher.Main).
 * If that changes, wrap accesses in a Mutex; for now the cost of synchronisation is unjustified.
 */
internal class LruStopCache(private val maxSize: Int) {
    private val backing: LinkedHashMap<StopId, Stop> = LinkedHashMap()

    fun putAll(stops: Collection<Stop>) {
        stops.forEach(::put)
    }

    fun put(stop: Stop) {
        // Remove-then-add bumps recency for a stop we've already cached. LinkedHashMap with
        // `accessOrder = true` would also work, but we'd still need the explicit eviction.
        backing.remove(stop.id)
        backing[stop.id] = stop
        while (backing.size > maxSize) {
            val eldest = backing.keys.iterator().next()
            backing.remove(eldest)
        }
    }

    fun clear() {
        backing.clear()
    }

    fun snapshot(): List<Stop> = backing.values.toList()

    fun size(): Int = backing.size
}
