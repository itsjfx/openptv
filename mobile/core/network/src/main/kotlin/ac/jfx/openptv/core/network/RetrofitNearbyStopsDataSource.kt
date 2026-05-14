package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.model.toDomain
import javax.inject.Inject

/**
 * Retrofit-backed [NearbyStopsDataSource]. URL composition mirrors [RetrofitStopDetailDataSource]:
 * the base URL comes from the injected [BackendUrlProvider] (production reads
 * `SettingsRepository`, tests pass a lambda), and the absolute URL goes to Retrofit via `@Url`.
 *
 * PTV's path is `stops/location/{lat},{lng}` — the comma sits between two doubles, which Retrofit
 * URL-encodes when passed through `@Path` but leaves alone when passed through `@Url`. We compose
 * the URL by hand to keep the format the same as the documented endpoint.
 *
 * Latitude / longitude formatting: we cap at 6 decimal places via `"%.6f"`. Six decimals is ~11 cm
 * of resolution on Earth's surface, which is well past anything coarse location can return; using
 * Kotlin's `toString()` instead can emit values like `-37.81360000000001` which clutter the URL
 * without changing the resolved point.
 */
internal class RetrofitNearbyStopsDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val backendUrl: BackendUrlProvider,
    ) : NearbyStopsDataSource {
        override suspend fun stopsNear(
            coordinates: Coordinates,
            radiusMeters: Int,
        ): List<Stop> {
            val base = backendUrl.backendBaseUrl()
            val lat = "%.6f".format(coordinates.lat)
            val lng = "%.6f".format(coordinates.lng)
            val url = "${base}stops/location/$lat,$lng?max_distance=$radiusMeters"
            return api.stopsNearLocation(url).toDomain()
        }
    }
