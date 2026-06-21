package ac.jfx.openptv.core.model

/**
 * The geographic shape of a route, sourced from PTV's `GET /v3/stops/route/{route_id}/route_type/
 * {route_type}?include_geopath=true` (issue #187). The run-pattern map (issue #187) joins this onto
 * a [RunPattern] to draw the line plus stop markers.
 *
 * **Why this exists separately from [RunPattern].** The pattern endpoint
 * (`/v3/pattern/run/...`) returns `geopath: null` in practice even with `include_geopath=true`
 * (verified against live train + bus runs), and its `stops` sideload omits lat/lng. The
 * `stops/route` endpoint reliably returns both — one geopath segment per direction, and every
 * stop on the route with coordinates. The data layer fetches this and joins it onto the run's
 * pattern by `stop_id` + the run's `direction_id`.
 *
 * `geopathByDirection` is keyed by PTV `direction_id`; each value is a list of polyline segments
 * (each segment an ordered list of [Coordinates]). A route typically has two entries (one per
 * direction of travel). Empty when PTV returned no geopath — the consumer degrades gracefully.
 *
 * `stopCoordinates` maps each stop on the route to its location, so the run-pattern join can fill
 * in [RunPatternStop.coordinates] for the stops that run calls at.
 */
data class RouteShape(
    val geopathByDirection: Map<Int, List<List<Coordinates>>>,
    val stopCoordinates: Map<StopId, Coordinates>,
) {
    /**
     * The geopath segments for [directionId], falling back to *any* available direction's segments
     * when the exact direction is missing (PTV occasionally labels a run's `direction_id`
     * differently from the `stops/route` segment keys). Empty list when no geopath exists at all.
     */
    fun geopathFor(directionId: Int?): List<List<Coordinates>> {
        if (geopathByDirection.isEmpty()) return emptyList()
        directionId?.let { id -> geopathByDirection[id]?.let { return it } }
        return geopathByDirection.values.firstOrNull().orEmpty()
    }

    companion object {
        val EMPTY: RouteShape = RouteShape(emptyMap(), emptyMap())
    }
}
