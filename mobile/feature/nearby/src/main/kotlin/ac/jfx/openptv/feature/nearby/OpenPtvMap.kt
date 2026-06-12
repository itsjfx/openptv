package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * Thin wrapper over MapLibre Android. Accepts only domain types so feature tests can inject a
 * fake without booting MapLibre / OpenGL.
 *
 * The seam is an `interface` with a single `@Composable` rendering function, bound by Hilt
 * (`MapsModule.bindOpenPtvMap`) to [MapLibreOpenPtvMap] in production and to a fake via
 * `FakeMapsModule` in `@HiltAndroidTest` builds. Mirrors NIA's `core/ui/.../UserNewsResourceCard`
 * shape — one interface, one impl, swapped via `@TestInstallIn`.
 *
 * @see MapLibreOpenPtvMap Production impl wrapping MapLibre's `MapView` via `AndroidView`.
 */
interface OpenPtvMap {
    /**
     * Render the map.
     *
     * @param camera latest desired camera position; the impl recentres when this changes
     *   (the View deduplicates if the camera is already there).
     * @param userLocation current user fix; pass `null` to hide the location dot.
     * @param userBearing device heading in degrees clockwise from north, normalised to
     *   `[0, 360)`. `null` means "no compass / haven't fired yet" — the dot still renders, but
     *   without a heading cone. Issue #99.
     * @param pins stop markers to render; the impl decides clustering based on [camera.zoom].
     * @param isDark whether the host theme is in dark mode; used to pick the OpenFreeMap style
     *   variant. Passed from outside the `AndroidView` scope because composition locals don't
     *   propagate through `factory = { ... }`.
     * @param onCameraIdle called whenever MapLibre's camera-idle listener fires; the screen's
     *   ViewModel debounces this stream and re-queries [NearbyStopsRepository].
     * @param onCameraMoveStarted called the moment the camera begins moving, with a
     *   [CameraMoveReason] that distinguishes a user gesture from a programmatic animation we
     *   kicked off ourselves. The ViewModel uses this to cancel any in-flight pin fetch so a slow
     *   drag doesn't keep burning the PTV rate limit on viewports the user is panning past —
     *   issue #109. The reason is needed because the "show on map" focus path (issue #123 /
     *   PR #139) animates the camera programmatically; that programmatic move-started must not
     *   tear down the focus-suppression slot the VM uses to drop MapLibre's stale pre-animation
     *   idle. Paired with [onCameraIdle] which fires the eventual fetch once the camera settles.
     * @param onPinClicked called when the user taps a pin; the screen renders a bottom sheet
     *   from this.
     */
    @Composable
    @Suppress("LongParameterList") // 9 params, all distinct concerns; collapsing would lose clarity
    fun Render(
        camera: OpenPtvCameraState,
        userLocation: Coordinates?,
        userBearing: Float?,
        pins: List<Stop>,
        isDark: Boolean,
        onCameraIdle: (OpenPtvCameraState) -> Unit,
        onCameraMoveStarted: (CameraMoveReason) -> Unit,
        onPinClicked: (Stop) -> Unit,
        modifier: Modifier,
    )
}

/**
 * Domain-typed reason for a camera-move-started callback. MapLibre exposes an `int` with three
 * codes (gesture, developer animation, API animation); we collapse the two "we initiated this"
 * codes into [PROGRAMMATIC] so the ViewModel only has to branch on "is this the user, or us?".
 *
 * Lives here rather than on [MapLibreOpenPtvMap] so the [OpenPtvMap] seam never leaks an
 * `org.maplibre.android.*` type into the ViewModel / Screen layer.
 */
enum class CameraMoveReason {
    /** User pan / pinch. The VM treats this as "the user has taken control of the camera". */
    USER_GESTURE,

    /**
     * Move triggered by our own `animateCamera` / `setCameraPosition` call (issue #123 focus,
     * style-load initial centre, etc.). The VM keeps its focus-suppression slot armed across
     * these so MapLibre's stale pre-animation idle can still be dropped.
     */
    PROGRAMMATIC,
}

/**
 * Camera-state projection. Carries everything the ViewModel needs to drive a fetch (centre, zoom)
 * — does NOT carry pitch or bearing because Phase 05 doesn't tilt or rotate the map. Adding them
 * later is a non-breaking extension.
 *
 * `@Immutable` so Compose's stable-class inference treats this as a comparable value when it
 * flows through a `remember(...)` slot.
 */
@Immutable
data class OpenPtvCameraState(
    val centre: Coordinates,
    val zoom: Double,
)
