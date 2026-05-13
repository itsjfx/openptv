package ac.jfx.openptv.core.model

/**
 * Stop metadata enriched with the routes that serve it. Loaded once when the stop-detail screen
 * opens; the live departures list ticks separately via [Departure].
 *
 * Splitting `stop` and `servingRoutes` into two fields rather than a flat structure means the
 * header (`Stop`) can share rendering with the search row, and the route-chip strip can reuse
 * the same `Route` projection in future phases (route detail in Phase 06).
 */
data class StopDetail(
    val stop: Stop,
    val servingRoutes: List<Route>,
)

/**
 * Minimal route projection — id, mode, the short name commuters recognise ("19", "Mernda").
 * Direction-by-route is a stop-detail screen detail that lives on [Departure].
 */
data class Route(
    val id: RouteId,
    val number: String,
    val name: String,
    val routeType: RouteType,
)
