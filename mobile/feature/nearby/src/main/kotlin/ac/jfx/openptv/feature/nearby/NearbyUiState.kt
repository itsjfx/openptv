package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop

/**
 * Screen state for the nearby map. Sealed so the screen branches on the variant — permission
 * gating shows a rationale dialog / denied banner, "loaded" shows the map.
 *
 * The map screen always renders some map — even pre-grant we centre on Melbourne CBD so the user
 * sees Victoria from frame one. The variants describe the **permission overlay** and the
 * **fetched-pin state**, not the map itself, which always exists.
 *
 * **Filter source-of-truth.** [routeTypeFilter] hangs off both [Loaded] and [PermissionDenied]
 * because both can fetch + display pins. Issue #80 (the bottom-sheet "scrollable list of nearby
 * stops") subscribes to the same `StateFlow<NearbyUiState>` that drives the map, so the filter
 * applies to both surfaces consistently — there's no second filter knob.
 */
sealed interface NearbyUiState {
    /**
     * The set of [RouteType]s the user wants to see. Empty set means "all types" — the same
     * shape as `NearbyStopsRepository.stopsNear(routeTypes = emptySet())`. The UI treats an
     * unselected chip the same way: grey out, no API filter.
     */
    val routeTypeFilter: Set<RouteType>

    /** First entry, permission not yet asked. Shows rationale dialog over the CBD-centred map. */
    data object PermissionUnasked : NearbyUiState {
        override val routeTypeFilter: Set<RouteType> = emptySet()
    }

    /** User denied permission. Shows a banner with an "Open Settings" CTA + CBD-centred map. */
    data class PermissionDenied(
        val camera: OpenPtvCameraState,
        val pins: List<Stop>,
        override val routeTypeFilter: Set<RouteType> = emptySet(),
    ) : NearbyUiState

    /** Permission granted (or denied + dismissed). Normal map operation. */
    data class Loaded(
        val camera: OpenPtvCameraState,
        val pins: List<Stop>,
        val userLocation: Coordinates?,
        val isFollowingUser: Boolean,
        val pendingSheet: SheetState,
        val showEmptyHint: Boolean,
        override val routeTypeFilter: Set<RouteType> = emptySet(),
    ) : NearbyUiState
}

/**
 * Pin-tap → bottom-sheet state. `Closed` means no sheet is shown; `Open` carries the tapped
 * stop along with the tap-time fetch results so the sheet can surface routes + realtime
 * departures without a second screen.
 *
 * The repository fetches that fill [StopBottomSheet.routes] / [StopBottomSheet.departures]
 * happen in the ViewModel — [SheetState] is purely the projection the screen renders.
 */
sealed interface SheetState {
    data object Closed : SheetState

    data class Open(val sheet: StopBottomSheet) : SheetState
}

/**
 * Projection rendered in the bottom sheet for a tapped stop.
 *
 * - [routes] is `null` while the routes fetch is in flight, then the resolved list (empty list
 *   means "the stop has no routes per PTV", which is rare but possible).
 * - [departures] is `null` while the first poll is in flight, then the resolved list capped at
 *   [DEPARTURES_PREVIEW_LIMIT]. The polling tick swaps this in place every 30 s.
 * - Either or both can land in [hadError] if the corresponding fetch failed; we keep the sheet
 *   open with whatever we already have (the stale-but-shown contract everywhere else in the app
 *   uses) and render an inline error chip.
 */
data class StopBottomSheet(
    val stop: Stop,
    val routes: List<Route>? = null,
    val departures: List<Departure>? = null,
    val hadError: Boolean = false,
) {
    companion object {
        /**
         * Maximum number of next-departure rows to surface in the bottom sheet. The screen is
         * a peek surface — full timetables live in stop-detail, so we cap at three to keep the
         * sheet small enough to not need scrolling on a typical phone.
         */
        const val DEPARTURES_PREVIEW_LIMIT: Int = 3
    }
}
