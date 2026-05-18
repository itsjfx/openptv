package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.model.toDomain
import javax.inject.Inject

/**
 * Retrofit-backed [NearbyStopsDataSource]. URL composition mirrors [RetrofitStopDetailDataSource]:
 * the absolute URL comes from the injected [PtvUrlResolver] (production reads
 * `SettingsRepository`, tests pass a lambda).
 *
 * PTV's path is `stops/location/{lat},{lng}` — the comma sits between two doubles, which Retrofit
 * URL-encodes when passed through `@Path` but leaves alone when passed through `@Url`. We compose
 * the URL by hand to keep the format the same as the documented endpoint.
 *
 * Latitude / longitude formatting: we cap at 6 decimal places via `"%.6f"`. Six decimals is ~11 cm
 * of resolution on Earth's surface, which is well past anything coarse location can return; using
 * Kotlin's `toString()` instead can emit values like `-37.81360000000001` which clutter the URL
 * without changing the resolved point.
 *
 * `route_types` is repeated once per requested mode in the query string — PTV's documented
 * convention for "any of these N types". The filter ordering is sorted-by-wire-code so identical
 * filter sets produce identical URLs (cache-friendly + makes test assertions deterministic).
 */
internal class RetrofitNearbyStopsDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val urlResolver: PtvUrlResolver,
    ) : NearbyStopsDataSource {
        override suspend fun stopsNear(
            coordinates: Coordinates,
            radiusMeters: Int,
            routeTypes: Set<RouteType>,
        ): List<Stop> {
            val lat = "%.6f".format(coordinates.lat)
            val lng = "%.6f".format(coordinates.lng)
            val routeTypeQuery =
                routeTypes
                    .map { it.toPtvCode() }
                    .toSortedSet()
                    .joinToString(separator = "") { "&route_types=$it" }
            val path = "stops/location/$lat,$lng?max_distance=$radiusMeters$routeTypeQuery"
            return api.stopsNearLocation(urlResolver.resolve(path)).toDomain()
        }
    }
