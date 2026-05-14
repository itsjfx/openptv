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
)

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
