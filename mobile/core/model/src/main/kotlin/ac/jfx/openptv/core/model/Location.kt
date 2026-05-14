package ac.jfx.openptv.core.model

/**
 * Geographic coordinate pair in WGS-84 decimal degrees. Used by [LocationProvider] (in
 * `:core:common`), the upcoming `NearbyStopsRepository`, and any future "distance-from-me"
 * consumer (favourites' Nearest sort).
 *
 * Pure data class rather than `@JvmInline value class` because two `Double` fields cannot fit
 * inside a single inline-class slot — the JVM would box them anyway, so the data-class shape
 * gives us free `equals` / `hashCode` / `copy` at no runtime cost.
 *
 * Range invariants (-90..90 / -180..180) are NOT enforced at construction time. PTV-supplied
 * coordinates and `LocationManager` callbacks already live inside those bounds; eagerly throwing
 * here would just push noise into mappers without catching real bugs. A `require(...)` block can
 * be added later if a consumer ever materialises a Coordinates from user input.
 */
data class Coordinates(
    val lat: Double,
    val lng: Double,
) {
    /**
     * Great-circle distance to [other] in metres, via the haversine formula. Used by the favourites
     * "Nearest" sort (issue #37) and any future "distance from me" surface.
     *
     * Haversine is the small-distance default — it ignores the WGS-84 oblateness in favour of a
     * spherical-earth model with `MEAN_EARTH_RADIUS_M = 6_371_008.8`. The error at PTV's
     * latitudes (≤ 60° in either direction) is well under 0.5% — fine for ordering rows by
     * proximity. If a future surface needs sub-metre accuracy (route-shape snapping?), swap to a
     * Vincenty-formula impl behind the same signature.
     */
    fun distanceTo(other: Coordinates): Double {
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val deltaLat = Math.toRadians(other.lat - lat)
        val deltaLng = Math.toRadians(other.lng - lng)

        val sinHalfDeltaLat = Math.sin(deltaLat / 2)
        val sinHalfDeltaLng = Math.sin(deltaLng / 2)
        val a =
            sinHalfDeltaLat * sinHalfDeltaLat +
                Math.cos(lat1) * Math.cos(lat2) * sinHalfDeltaLng * sinHalfDeltaLng
        val c = 2 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
        return MEAN_EARTH_RADIUS_M * c
    }

    private companion object {
        // Mean Earth radius per IUGG; same constant the haversine references use.
        private const val MEAN_EARTH_RADIUS_M: Double = 6_371_008.8
    }
}

/**
 * Axis-aligned bounding box. `southWest` is the smaller-lat / smaller-lng corner and `northEast`
 * is the larger-lat / larger-lng corner — the same convention MapLibre's `LatLngBounds` uses, so
 * the map module (issue #37) can pass these straight through to camera bounds.
 *
 * Does NOT handle the antimeridian (a bounds straddling 180°E/W would need `west > east`). PTV's
 * coverage area sits comfortably inside one hemisphere, so the simple shape is enough until a
 * use case proves otherwise.
 */
data class Bounds(
    val southWest: Coordinates,
    val northEast: Coordinates,
)
