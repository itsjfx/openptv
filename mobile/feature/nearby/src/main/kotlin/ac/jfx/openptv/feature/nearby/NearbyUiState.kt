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
 * **Filter source-of-truth.** [routeTypeFilter] hangs off every variant because the chip strip
 * lives over the map at all times. Issue #80 (the bottom-sheet "scrollable list of nearby stops")
 * subscribes to the same `StateFlow<NearbyUiState>` that drives the map, so the filter applies to
 * both surfaces consistently — there's no second filter knob.
 *
 * **Invariant:** [routeTypeFilter] is **always non-empty**. An empty set would show zero stops
 * everywhere, which is a dead-end UX — the chip toggle in [NearbyViewModel] no-ops a tap on the
 * last selected chip. The default is [DEFAULT_FILTER] (every visible mode on).
 */
sealed interface NearbyUiState {
    /**
     * The set of [RouteType]s the user has selected. Carried verbatim into PTV's `route_types`
     * query parameter, plus belt-and-braces filtered at the screen render seam. Always non-empty
     * — see the interface kdoc.
     */
    val routeTypeFilter: Set<RouteType>

    /** First entry, permission not yet asked. Shows rationale dialog over the CBD-centred map. */
    data object PermissionUnasked : NearbyUiState {
        override val routeTypeFilter: Set<RouteType> = DEFAULT_FILTER
    }

    /** User denied permission. Shows a banner with an "Open Settings" CTA + CBD-centred map. */
    data class PermissionDenied(
        val camera: OpenPtvCameraState,
        val pins: List<Stop>,
        override val routeTypeFilter: Set<RouteType> = DEFAULT_FILTER,
    ) : NearbyUiState

    /**
     * Permission granted (or denied + dismissed). Normal map operation.
     *
     * [userBearing] is the device heading in degrees clockwise from north, normalised to
     * `[0, 360)`. `null` means "no compass / sensor hasn't fired yet" — the screen renders the
     * blue dot without a heading cone in that case (issue #99 acceptance: "device-doesn't-have-
     * compass case — no cone, just dot"). Same null-means-absent convention as [userLocation].
     */
    data class Loaded(
        val camera: OpenPtvCameraState,
        val pins: List<Stop>,
        val userLocation: Coordinates?,
        val userBearing: Float? = null,
        val isFollowingUser: Boolean,
        val pendingSheet: SheetState,
        val showEmptyHint: Boolean,
        override val routeTypeFilter: Set<RouteType> = DEFAULT_FILTER,
    ) : NearbyUiState
}

/**
 * Initial filter — every visible transport mode is on. The screen's chip strip and
 * [NearbyViewModel] both honour the "filter is always non-empty" invariant; this is the
 * canonical "everything selected" set.
 *
 * Mirrors the chip strip in `NearbyScreen.kt` (Train / Tram / Bus / V/Line / Night Bus). [RouteType.Unknown]
 * is intentionally omitted — it's a runtime fallback for unexpected wire codes, not a user-facing
 * mode.
 */
val DEFAULT_FILTER: Set<RouteType> =
    setOf(RouteType.Train, RouteType.Tram, RouteType.Bus, RouteType.VLine, RouteType.NightBus)

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
 * One row in the bottom-sheet "nearby stops list" (issue #80). Carries the source [Stop] (kept
 * whole so a row tap can re-use [NearbyViewModel.onPinClicked]) plus the distance from the user's
 * current fix at the time the row was projected.
 *
 * The projection is screen-local — each row is the same `Stop` the map renders, augmented with a
 * [distanceMetres]. The screen sorts by [distanceMetres] ascending. When the user has no fix
 * (denied permission, or `lastKnown()` returned null), [distanceMetres] is `null` and the screen
 * falls back to repository order.
 */
data class NearbyListRow(
    val stop: Stop,
    val distanceMetres: Double?,
)

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
