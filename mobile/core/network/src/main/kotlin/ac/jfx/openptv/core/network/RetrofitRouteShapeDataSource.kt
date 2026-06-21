package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteShape
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.network.model.toDomain
import javax.inject.Inject

/**
 * Retrofit-backed [RouteShapeDataSource] (issue #187). URL composition mirrors the other data
 * sources in this module: absolute URL from [PtvUrlResolver] (proxy or signed-direct-PTV picked
 * per call).
 *
 * Hits `stops/route/{route_id}/route_type/{route_type}?include_geopath=true`. This is the reliable
 * geopath source: the run-pattern endpoint returns `geopath: null` even with `include_geopath=true`
 * (verified against live train + bus runs), whereas this endpoint returns both the polyline (one
 * segment per direction) and every stop's coordinates.
 */
internal class RetrofitRouteShapeDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val urlResolver: PtvUrlResolver,
    ) : RouteShapeDataSource {
        override suspend fun getRouteShape(
            routeId: RouteId,
            routeType: RouteType,
        ): RouteShape {
            val typeCode = routeType.toPtvCode()
            val path =
                "stops/route/${routeId.value}/route_type/$typeCode?include_geopath=true"
            return api.getRouteShape(urlResolver.resolve(path)).toDomain()
        }
    }
