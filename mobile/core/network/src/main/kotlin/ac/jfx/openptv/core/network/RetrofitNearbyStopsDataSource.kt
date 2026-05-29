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
            val path =
                "stops/location/$lat,$lng" +
                    "?max_distance=$radiusMeters&max_results=$MAX_RESULTS$routeTypeQuery"
            return api.stopsNearLocation(urlResolver.resolve(path)).toDomain()
        }

        private companion object {
            /**
             * Cap on stops returned per fetch. PTV's `stops/location` endpoint defaults
             * `max_results` to **30** when the parameter is omitted, and applies it *before*
             * `max_distance` — so a zoomed-out request only ever gets the 30 stops nearest the
             * camera centre, all bunched in the middle of the viewport, no matter how wide
             * `max_distance` is. That is what made the map look empty outside the CBD even after
             * issue #124 raised the radius cap.
             *
             * 600 is sized to fill a metro-wide (zoom ~12) viewport with pins while staying well
             * under the [NearbyViewModel] LRU stop cache bound (2000). At street zoom the disc
             * simply doesn't contain this many stops, so PTV returns however many actually exist —
             * the cap only bites at low zoom, which is exactly where we want it.
             */
            private const val MAX_RESULTS = 600
        }
    }
