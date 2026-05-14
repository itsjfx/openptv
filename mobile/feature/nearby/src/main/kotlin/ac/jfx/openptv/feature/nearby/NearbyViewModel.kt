package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.NearbyStopsRepository
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the nearby map screen (issue #37). Owns the camera position, the pin list, the
 * permission state, the follow-me toggle, and the pin-tap bottom sheet.
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
 */
@HiltViewModel
class NearbyViewModel
    @Inject
    constructor(
        private val locationProvider: LocationProvider,
        private val nearbyStopsRepository: NearbyStopsRepository,
    ) : ViewModel() {
        private val _uiState: MutableStateFlow<NearbyUiState> =
            MutableStateFlow(NearbyUiState.PermissionUnasked)
        val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

        /**
         * Inputs to the debounced fetch pipeline. `replay = 1` so an emit that fires before the
         * collector in [wireCameraDebounce] is scheduled (e.g. the initial seed inside
         * `onPermissionResult` while we're still under a `StandardTestDispatcher`) is still
         * delivered — the collector picks it up as a replay value as soon as it starts.
         */
        private val cameraIdles: MutableSharedFlow<OpenPtvCameraState> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 1)

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
                            isFollowingUser = fix != null,
                            pendingSheet = SheetState.Closed,
                            showEmptyHint = false,
                        )
                    // Seed the debounce so the initial fetch lands without a manual pan.
                    cameraIdles.tryEmit(initialCamera)
                } else {
                    _uiState.value =
                        NearbyUiState.PermissionDenied(
                            camera = OpenPtvCameraState(centre = MELBOURNE_CBD, zoom = INITIAL_ZOOM),
                            pins = emptyList(),
                        )
                }
            }
        }

        /** Called from MapLibre's camera-idle listener through the [OpenPtvMap] callback. */
        fun onCameraIdle(camera: OpenPtvCameraState) {
            val current = _uiState.value
            if (current is NearbyUiState.Loaded) {
                // A manual pan disengages follow-me — same convention every other map app uses.
                val stillFollowing =
                    current.isFollowingUser &&
                        current.userLocation != null &&
                        camera.centre.distanceTo(current.userLocation) < FOLLOW_LEASH_METERS
                _uiState.value = current.copy(camera = camera, isFollowingUser = stillFollowing)
            }
            cameraIdles.tryEmit(camera)
        }

        /** Called when the user taps a pin. */
        fun onPinClicked(stop: Stop) {
            val current = _uiState.value
            if (current is NearbyUiState.Loaded) {
                _uiState.value = current.copy(pendingSheet = SheetState.Open(stop))
            }
        }

        /** Called when the bottom sheet is dismissed without selecting "View stop". */
        fun onSheetDismissed() {
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
                cameraIdles
                    .debounce(CAMERA_IDLE_DEBOUNCE_MS)
                    // `collectLatest` cancels an in-flight fetch if the user pans again mid-call.
                    // Avoids a slow fetch landing on top of a fresher one and overwriting the pins
                    // the user is currently looking at.
                    .collectLatest { camera ->
                        val radius = radiusForZoom(camera.zoom)
                        val result = nearbyStopsRepository.stopsNear(camera.centre, radius)
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

            /** Initial zoom — covers central CBD comfortably. */
            internal const val INITIAL_ZOOM: Double = 12.0

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
