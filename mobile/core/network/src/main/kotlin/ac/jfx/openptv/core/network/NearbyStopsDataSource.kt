package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop

/**
 * Network-layer "stops near a point" seam. Mirrors [StopSearchDataSource] / [StopDetailDataSource]:
 * public interface that exposes only domain types ([Coordinates] + [Stop]); the Retrofit-backed
 * impl and the underlying [BackendApiService] stay `internal` to this module.
 *
 * [radiusMeters] is forwarded to PTV's `max_distance` query parameter. The PTV API's documented
 * default is 300 m; we let the caller pick because the nearby map sets it from the visible
 * viewport diagonal, which can range from a couple hundred metres to several kilometres at low
 * zoom. The impl also forwards a `max_results` override — PTV defaults that to 30 and applies it
 * before `max_distance`, so without it a wide-radius fetch only returns the 30 stops nearest the
 * centre (issue #124).
 *
 * [routeTypes] forwards to PTV's `route_types` query parameter (repeated once per requested
 * mode). An empty set means "all types" — PTV omits the parameter and returns every mode. Used
 * by `:feature:nearby`'s filter chips (#79) to scope the result without a client-side filter.
 */
interface NearbyStopsDataSource {
    suspend fun stopsNear(
        coordinates: Coordinates,
        radiusMeters: Int,
        routeTypes: Set<RouteType> = emptySet(),
    ): List<Stop>
}
