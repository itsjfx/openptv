package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.model.Bounds
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType

/**
 * Everything the run-pattern map (issue #187) needs to render, projected off the domain
 * [`RunPattern`][ac.jfx.openptv.core.model.RunPattern] so the map composable never joins maps or
 * touches domain types it doesn't need.
 *
 * `hasGeometry` is the graceful-degradation switch: false when PTV returned no geopath *and* no
 * stop with coordinates, in which case the screen hides the map section entirely rather than
 * showing an empty grey tile. A line-only or stops-only shape still counts as "has geometry".
 *
 * `bounds` is the axis-aligned box enclosing every drawn point (line + markers), used to frame the
 * camera on first render. Null when there's nothing to frame.
 */
data class RunPatternMapData(
    val routeType: RouteType,
    /** Polyline segments; each is an ordered list of coordinates. Empty when PTV gave no geopath. */
    val polyline: List<List<Coordinates>>,
    val markers: List<RunPatternMapMarker>,
    val bounds: Bounds?,
) {
    val hasGeometry: Boolean
        get() = polyline.any { it.isNotEmpty() } || markers.isNotEmpty()

    companion object {
        /** Combine the line points and marker points into one fitting box; null when both empty. */
        fun boundsOf(
            polyline: List<List<Coordinates>>,
            markers: List<RunPatternMapMarker>,
        ): Bounds? {
            val all = polyline.flatten() + markers.map { it.coordinates }
            if (all.isEmpty()) return null
            val minLat = all.minOf { it.lat }
            val maxLat = all.maxOf { it.lat }
            val minLng = all.minOf { it.lng }
            val maxLng = all.maxOf { it.lng }
            return Bounds(
                southWest = Coordinates(lat = minLat, lng = minLng),
                northEast = Coordinates(lat = maxLat, lng = maxLng),
            )
        }
    }
}

/**
 * One stop marker on the map. `isOrigin` is the "you are here" stop the user tapped through from
 * (issue #187 — drawn as the highlighted blue dot); `hasDeparted` dims past stops the same way the
 * timeline does.
 */
data class RunPatternMapMarker(
    val coordinates: Coordinates,
    val label: String,
    val isOrigin: Boolean,
    val hasDeparted: Boolean,
)
