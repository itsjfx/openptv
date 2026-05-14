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
     * @param pins stop markers to render; the impl decides clustering based on [camera.zoom].
     * @param isDark whether the host theme is in dark mode; used to pick the OpenFreeMap style
     *   variant. Passed from outside the `AndroidView` scope because composition locals don't
     *   propagate through `factory = { ... }`.
     * @param onCameraIdle called whenever MapLibre's camera-idle listener fires; the screen's
     *   ViewModel debounces this stream and re-queries [NearbyStopsRepository].
     * @param onPinClicked called when the user taps a pin; the screen renders a bottom sheet
     *   from this.
     */
    @Composable
    @Suppress("LongParameterList") // 7 params, all distinct concerns; collapsing would lose clarity
    fun Render(
        camera: OpenPtvCameraState,
        userLocation: Coordinates?,
        pins: List<Stop>,
        isDark: Boolean,
        onCameraIdle: (OpenPtvCameraState) -> Unit,
        onPinClicked: (Stop) -> Unit,
        modifier: Modifier,
    )
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
