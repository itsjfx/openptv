package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import kotlinx.datetime.Instant

/**
 * UI state for the stop-detail screen. The header and the departures list have independent
 * loading / error lifecycles — the header is a one-shot fetch, the list is a 30 s polling Flow —
 * so the screen-level state is a product of [HeaderState] and [DeparturesState]. Modelling them as
 * separate sealed types keeps each call-site's `when` exhaustive without explosion.
 *
 * [`isRefreshing`] is the pull-to-refresh indicator state. It flips on while a manual refresh is
 * in flight and back off the moment the next emission lands. The background 30 s tick doesn't toggle
 * it — that would jiggle the indicator twice a minute.
 *
 * `asOf` is the wall-clock instant the most recent successful list emission was observed; the UI
 * renders it as `As of HH:mm`. Null while the first successful fetch hasn't landed.
 */
data class StopDetailUiState(
    val header: HeaderState,
    val departures: DeparturesState,
    val isRefreshing: Boolean = false,
    val asOf: Instant? = null,
) {
    companion object {
        val Initial: StopDetailUiState =
            StopDetailUiState(
                header = HeaderState.Loading,
                departures = DeparturesState.Loading,
            )
    }
}

sealed interface HeaderState {
    data object Loading : HeaderState

    data class Loaded(val detail: StopDetail) : HeaderState

    data class Error(val reason: String) : HeaderState
}

sealed interface DeparturesState {
    data object Loading : DeparturesState

    /**
     * Departures grouped by `(routeId, directionId)` for the section list. Each [Group] carries
     * the route projection (for the badge / mode icon) so the UI doesn't have to cross-look-up.
     *
     * `isLoadingMore` flips true while a paginated fetch (either "show more" or
     * "scrolled past the bottom") is in flight; the UI uses it to render the tail spinner.
     */
    data class Loaded(
        val groups: List<Group>,
        val isLoadingMore: Boolean = false,
    ) : DeparturesState

    /** A successful fetch returned zero upcoming departures — last service of the day. */
    data object Empty : DeparturesState

    data class Error(val reason: String) : DeparturesState
}

/**
 * Pre-grouped section for the section list. Keeping the grouping in `UiState` (not Compose)
 * means the unit test asserts a stable order without reaching into the UI tree.
 *
 * `headerLabel` is "Route {number} · {direction}" — built at mapping time so the row composable
 * stays free of string concatenation. `route` is nullable because PTV occasionally references a
 * routeId in a departure that isn't in the `StopDetail.servingRoutes` payload (route filtering
 * disagreement between endpoints); rather than drop the row, we render it with a placeholder
 * badge and log the discrepancy later.
 *
 * `expanded` tracks the per-group disclosure state (issue #68). Collapsed groups render the
 * first [COLLAPSED_VISIBLE] entries plus a "show N more" affordance; expanded groups render
 * every loaded entry. Paging only kicks in once a group is expanded — the user pulled the trigger
 * by tapping "show more", and now scrolling deeper into that group consumes more pages.
 */
data class Group(
    val key: GroupKey,
    val route: Route?,
    val routeType: RouteType,
    val headerLabel: String,
    val departures: List<Departure>,
    val expanded: Boolean = false,
    /**
     * Whether the user has favourited this `(stopId, routeId, directionId)` triple. Populated by
     * the ViewModel from `ObserveFavouritesUseCase`. Defaults to `false` so a freshly-loaded
     * group renders the hollow glyph until the first favourites emission lands (which is
     * immediate — `StateFlow` is hot — so the user never sees a "wrong" state for more than a
     * frame).
     */
    val isFavourite: Boolean = false,
)

/** Section key — pair of (routeId, directionId). Stable for `key=` slots in `LazyColumn`. */
data class GroupKey(val routeId: Int, val directionId: Int)

/**
 * How many entries a collapsed [Group] shows by default (issue #68). The PTV app uses 3; we
 * mirror that. Tweakable in one place if the design changes.
 */
const val COLLAPSED_VISIBLE: Int = 3

/**
 * How many entries each paginated [loadMore] call asks PTV for (per route, since the API applies
 * `max_results` per route once `route_id` is omitted). Sized so a single page reliably crosses
 * midnight on quieter routes — the V/Line lines run an hourly service after 23:00, so a 10-row
 * page is good for several hours of headway.
 */
const val PAGE_SIZE: Int = 10
