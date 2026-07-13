package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop

/**
 * Search-facing slice of the stop repository. Domain layer (ViewModels / use cases) sees only
 * this interface; the network-backed impl is wired by Hilt via [DataModule].
 *
 * Errors are folded into [Result.Error] rather than thrown, so callers never need a try/catch
 * — they pattern-match the result and map each branch onto a UI state.
 *
 * [routeTypes] scopes the search to the given modes (issue #213) — PTV's `route_types` query
 * parameter, repeated once per mode. An empty set means "all modes": the parameter is omitted
 * and PTV returns everything, the same convention as [NearbyStopsRepository.stopsNear].
 */
interface StopSearchRepository {
    suspend fun searchStops(
        term: String,
        routeTypes: Set<RouteType> = emptySet(),
    ): Result<List<Stop>>
}
