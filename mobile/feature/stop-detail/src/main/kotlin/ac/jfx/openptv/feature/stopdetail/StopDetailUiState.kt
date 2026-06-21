package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Disruption
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
 *
 * `disruptions` is the de-duplicated union of every disruption attached to the current departures
 * (issue #177) — the stop-level banner above the list. Empty when nothing affects this stop's
 * services. Sourced from the same departures poll the rows render, so no extra network call.
 */
data class StopDetailUiState(
    val header: HeaderState,
    val departures: DeparturesState,
    val isRefreshing: Boolean = false,
    val asOf: Instant? = null,
    val disruptions: List<Disruption> = emptyList(),
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
 * `visibleCount` is how many of the group's [departures] to render right now (issue #126). It
 * starts at [INITIAL_VISIBLE] and grows by [SHOW_MORE_STEP] every time the user taps "Show more".
 * There is no "expand to show everything" or scroll-triggered paging any more — the user reveals
 * exactly the next [SHOW_MORE_STEP] rows per tap, and the ViewModel fetches another page from PTV
 * only when a tap runs past the rows already cached for this group.
 *
 * `canShowMore` is whether to render the generic "Show more" affordance under the group. It stays
 * true until a fetch anchored at this group's tail comes back empty (the group has reached the end
 * of service), at which point the button is dropped.
 */
data class Group(
    val key: GroupKey,
    val routes: List<Route>,
    val routeType: RouteType,
    val headerLabel: String,
    val departures: List<Departure>,
    val visibleCount: Int = INITIAL_VISIBLE,
    val canShowMore: Boolean = false,
    /**
     * Whether the user has favourited the `(stopId, destinationKey)` represented by this group.
     * Populated by the ViewModel from `ObserveFavouritesUseCase`. Defaults to `false`.
     */
    val isFavourite: Boolean = false,
    /**
     * True when this group is the favourite-tap-through pinned destination (issue #78). Pinned
     * groups sort to the top of the list so the user lands on the destination they came in for,
     * with the rest of the stop's services still visible underneath.
     */
    val isPinned: Boolean = false,
)

/**
 * Section key — the destination/direction name normalised to lowercase. Stable for `key=` slots
 * in `LazyColumn` and stable across head polls / page fetches as long as PTV reports the same
 * destination string.
 */
data class GroupKey(val destination: String)

/**
 * How many entries a [Group] shows on first render (issue #126). The PTV app uses 3; we mirror
 * that. Matches [ac.jfx.openptv.core.data.DepartureRepository.INITIAL_PAGE_SIZE_PER_ROUTE] so the
 * head poll fetches exactly what the initial window shows.
 */
const val INITIAL_VISIBLE: Int = 3

/**
 * How many more entries each "Show more" tap reveals — and how many per route the follow-up
 * [loadMore] fetch asks PTV for when the tap runs past the cached rows (issue #126). The API
 * applies `max_results` per route once `route_id` is omitted, so a tap on one destination pulls
 * the next [SHOW_MORE_STEP] for every route and tops up the other groups' caches for free.
 */
const val SHOW_MORE_STEP: Int = 3
