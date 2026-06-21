package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Repository for live [Departure]s at a stop. Three surfaces:
 *
 *  - [getDepartures] — one-shot fetch, useful for pull-to-refresh and for the Glance widget
 *    (which doesn't keep a long-lived collector around).
 *  - [observeDepartures] — a hot-ish `Flow<Result<List<Departure>>>` that re-emits on a 30 s
 *    tick. Collector lifetime drives the polling loop: cancelling the collector cancels the
 *    underlying `delay` and `fetch`, no orphan coroutines. The Flow emits `Result.Loading`
 *    immediately on subscribe and on each subsequent refresh, then the fetched result. The head
 *    poll asks PTV for [INITIAL_PAGE_SIZE_PER_ROUTE] entries per route via `max_results`, which
 *    keeps the live tick cheap while still giving each route enough rows to show its collapsed
 *    head.
 *  - [loadMore] — paginated one-shot anchored at a given instant (`date_utc`). Powers both the
 *    "show more" expansion within a group and the "scroll past midnight" tail. The caller is
 *    expected to hand the time-anchor in (typically the `effectiveDepartureUtc` of the last row
 *    they currently hold) and merge the new entries into their view by `runRef`.
 *
 * All surfaces fold non-cancellation throwables into [Result.Error]; cancellation propagates.
 *
 * Errors mid-poll do not break the loop — the next tick recovers naturally. Tests pin that
 * behaviour in `DepartureRepositoryImplTest`.
 */
interface DepartureRepository {
    /**
     * One-shot fetch returning both the [Departure] list and the [DeparturesAtStop.routes]
     * sideload PTV emits in the same response. The favourites screen needs the route join to
     * render the line-name badge for the next service (issue #137). Consumers that don't care
     * about routes can read `result.data.departures` and discard the rest — the polling
     * [observeDepartures] surface already does this internally.
     */
    suspend fun getDepartures(
        stopId: StopId,
        routeType: RouteType,
        at: Instant? = null,
    ): Result<DeparturesAtStop>

    /**
     * @param at optional time anchor (issue #182). When `null` (the default) the stream behaves
     *   exactly as it always has — every tick anchors `date_utc` at live "now" minus the grace
     *   window. When a non-null instant is supplied, every fetch anchors `date_utc` at that fixed
     *   instant so the user can view departures "around" a chosen moment. The polling cadence is
     *   unchanged at the repository seam; the ViewModel decides whether to keep a custom-time
     *   collector alive (it doesn't — a pinned time is a static snapshot, no live polling).
     */
    fun observeDepartures(
        stopId: StopId,
        routeType: RouteType,
        at: Instant? = null,
    ): Flow<Result<List<Departure>>>

    /**
     * One-shot fetch anchored at [after] for paging. Asks PTV for up to [maxResults] entries per
     * route starting at the given instant; the live tick continues independently and keeps the
     * head of the list fresh.
     */
    suspend fun loadMore(
        stopId: StopId,
        routeType: RouteType,
        after: Instant,
        maxResults: Int,
    ): Result<List<Departure>>

    companion object {
        /**
         * Head-poll `max_results` parameter. PTV applies this per route, so a stop with N routes
         * returns up to `N * INITIAL_PAGE_SIZE_PER_ROUTE` rows on each tick. Sized to exactly cover
         * the initial "show 3" window (issue #126) and no more — extra rows are fetched lazily by
         * [loadMore] only when the user taps "Show more", so a quiet stop with many routes doesn't
         * pull a wall of departures it never displays.
         */
        const val INITIAL_PAGE_SIZE_PER_ROUTE: Int = 3
    }
}
