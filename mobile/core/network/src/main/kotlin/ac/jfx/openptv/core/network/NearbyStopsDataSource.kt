package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop

/**
 * Network-layer "stops near a point" seam. Mirrors [StopSearchDataSource] / [StopDetailDataSource]:
 * public interface that exposes only domain types ([Coordinates] + [Stop]); the Retrofit-backed
 * impl and the underlying [BackendApiService] stay `internal` to this module.
 *
 * [radiusMeters] is forwarded to PTV's `max_distance` query parameter. The PTV API's documented
 * default is 300 m; we let the caller pick because the nearby map sets it from the visible
 * viewport diagonal, which can range from a couple hundred metres to several kilometres at low
 * zoom.
 */
interface NearbyStopsDataSource {
    suspend fun stopsNear(
        coordinates: Coordinates,
        radiusMeters: Int,
    ): List<Stop>
}
