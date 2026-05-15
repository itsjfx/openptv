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
) {
    /**
     * The label commuters know the route by. See [routeDisplayLabel] for the per-`route_type`
     * rule. Use this in any UI that previously rendered `#<routeId>` — the internal PTV
     * `route_id` integer is not useful to users (issue #88).
     */
    val displayLabel: String
        get() = routeDisplayLabel(routeType, number, name, id)
}

/**
 * Pick the user-facing label for a route given its `route_type`. Branched out from [Route] so
 * cached projections that only carry `(routeType, routeNumber, routeName)` (e.g. the favourites
 * DB row) can reuse the exact same rule without inflating themselves back to a [Route].
 *
 * PTV's `route_number` is the public service number that appears on the front of the vehicle —
 * meaningful for trams ("96", "109", "48"), buses ("612") and night buses. Trains and V/Line
 * services don't have a number; PTV returns an empty `route_number` on those and the line name
 * ("Lilydale", "Sandringham") in `route_name` is what the public knows them by.
 *
 * Fallbacks: if the preferred field is empty (PTV is occasionally inconsistent — e.g. train
 * `route_name` empty on a few stale rows), try the other field, then fall back to `#<routeId>`
 * so two routes can still be told apart in a multi-route block header.
 */
fun routeDisplayLabel(
    routeType: RouteType,
    routeNumber: String,
    routeName: String,
    routeId: RouteId,
): String {
    val (preferred, alternate) =
        when (routeType) {
            RouteType.Train, RouteType.VLine -> routeName to routeNumber
            RouteType.Tram, RouteType.Bus, RouteType.NightBus -> routeNumber to routeName
            RouteType.Unknown -> routeNumber to routeName
        }
    return preferred.ifBlank { alternate }.ifBlank { "#${routeId.value}" }
}
