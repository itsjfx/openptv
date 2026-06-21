package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteShape
import ac.jfx.openptv.core.model.RouteType

/**
 * Network boundary for a route's geographic shape (issue #187): its geopath polyline plus the
 * coordinates of every stop on the route. Throws on transport / parse failures — `:core:data`
 * owns the `Result` wrapping, same contract as [RunPatternDataSource].
 */
interface RouteShapeDataSource {
    suspend fun getRouteShape(
        routeId: RouteId,
        routeType: RouteType,
    ): RouteShape
}
