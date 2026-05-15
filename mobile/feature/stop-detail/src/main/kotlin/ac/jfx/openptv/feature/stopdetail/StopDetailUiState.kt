package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
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
     * Departures grouped by destination (issue #87) for the section list. Each [Group] carries
     * the routes that contribute to it (multiple at busy interchanges like Richmond where five
     * lines all run to "City") so the UI can render per-row badges without a cross-lookup.
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
 * Groups are keyed by destination (direction name) so multiple routes that all run to the same
 * destination — e.g. every Burnley-group train at Richmond heading to "City" — appear in one
 * block (issue #87). Per-departure rows still show their own route badge, so the user can tell
 * which line a given service is.
 *
 * `headerLabel` is the destination ("City", "Mernda") — built at mapping time so the row
 * composable stays free of string concatenation. `routes` is the de-duplicated list of routes
 * inside the group, ordered by earliest departure. `routeType` is the screen's mode — every
 * row at a stop shares it. The list is nullable / possibly empty because PTV occasionally
 * references a routeId in a departure that isn't in the `StopDetail.servingRoutes` payload
 * (route filtering disagreement between endpoints); rather than drop the row, we render it with
 * a placeholder badge and log the discrepancy later.
 *
 * `expanded` tracks the per-group disclosure state (issue #68). Collapsed groups render the
 * first [COLLAPSED_VISIBLE] entries plus a "show N more" affordance; expanded groups render
 * every loaded entry. Paging only kicks in once a group is expanded — the user pulled the trigger
 * by tapping "show more", and now scrolling deeper into that group consumes more pages.
 */
data class Group(
    val key: GroupKey,
    val routes: List<Route>,
    val routeType: RouteType,
    val headerLabel: String,
    val departures: List<Departure>,
    val expanded: Boolean = false,
    /**
     * Whether the user has favourited the (stopId, routeId, directionId) triple represented by
     * this group. Only meaningful when the group contains a single route — see [favouriteTarget].
     * Populated by the ViewModel from `ObserveFavouritesUseCase`. Defaults to `false`.
     */
    val isFavourite: Boolean = false,
    /**
     * True when this group is the favourite-tap-through pinned destination (issue #78). Pinned
     * groups sort to the top of the list and start expanded, so the user lands on the route they
     * actually came in for, with the rest of the stop's services still visible underneath.
     */
    val isPinned: Boolean = false,
    /**
     * When the group contains exactly one route, this is the (routeId, direction) tuple the
     * favourite star toggles. Null when the group bundles multiple routes — the favourite model
     * is per-(routeId, directionId), so a "City" block at Richmond with five lines feeding into
     * it has no single favourite target and the star is hidden.
     */
    val favouriteTarget: FavouriteTarget? = null,
)

/**
 * Pair the favourite star needs to toggle: the specific route and direction this group's single
 * route serves. Only populated for single-route groups; multi-route groups (issue #87) set this
 * to null and hide the star.
 */
data class FavouriteTarget(
    val routeId: RouteId,
    val direction: Direction,
)

/**
 * Section key — the destination/direction name normalised to lowercase. Stable for `key=` slots
 * in `LazyColumn` and stable across head polls / page fetches as long as PTV reports the same
 * destination string.
 */
data class GroupKey(val destination: String)

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
