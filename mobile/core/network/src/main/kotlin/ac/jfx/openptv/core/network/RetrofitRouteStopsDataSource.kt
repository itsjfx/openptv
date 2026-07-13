package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.model.toSequenceMap
import javax.inject.Inject

/**
 * Retrofit-backed [RouteStopsDataSource] (issue #204). URL composition mirrors the other data
 * sources in this module: absolute URL from [PtvUrlResolver] (proxy or signed-direct-PTV picked
 * per call).
 *
 * Hits `stops/route/{route_id}/route_type/{route_type}?direction_id={d}` — the same endpoint as
 * [RetrofitRouteShapeDataSource] but without `include_geopath` (the sequences are wanted, not
 * the polyline) and with the direction pinned so `stop_sequence` is populated.
 */
internal class RetrofitRouteStopsDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val urlResolver: PtvUrlResolver,
    ) : RouteStopsDataSource {
        override suspend fun getStopSequences(
            routeId: RouteId,
            routeType: RouteType,
            directionId: DirectionId,
        ): Map<StopId, Int> {
            val typeCode = routeType.toPtvCode()
            val path =
                "stops/route/${routeId.value}/route_type/$typeCode?direction_id=${directionId.value}"
            return api.getRouteStops(urlResolver.resolve(path)).toSequenceMap()
        }
    }
