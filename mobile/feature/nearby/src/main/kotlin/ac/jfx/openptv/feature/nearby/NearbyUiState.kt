package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop

/**
 * Screen state for the nearby map. Sealed so the screen branches on the variant — permission
 * gating shows a rationale dialog / denied banner, "loaded" shows the map.
 *
 * The map screen always renders some map — even pre-grant we centre on Melbourne CBD so the user
 * sees Victoria from frame one. The variants describe the **permission overlay** and the
 * **fetched-pin state**, not the map itself, which always exists.
 */
sealed interface NearbyUiState {
    /** First entry, permission not yet asked. Shows rationale dialog over the CBD-centred map. */
    data object PermissionUnasked : NearbyUiState

    /** User denied permission. Shows a banner with an "Open Settings" CTA + CBD-centred map. */
    data class PermissionDenied(
        val camera: OpenPtvCameraState,
        val pins: List<Stop>,
    ) : NearbyUiState

    /** Permission granted (or denied + dismissed). Normal map operation. */
    data class Loaded(
        val camera: OpenPtvCameraState,
        val pins: List<Stop>,
        val userLocation: Coordinates?,
        val isFollowingUser: Boolean,
        val pendingSheet: SheetState,
        val showEmptyHint: Boolean,
    ) : NearbyUiState
}

/** Pin-tap → bottom-sheet state. `Closed` means no sheet is shown. */
sealed interface SheetState {
    data object Closed : SheetState

    data class Open(val stop: Stop) : SheetState
}
