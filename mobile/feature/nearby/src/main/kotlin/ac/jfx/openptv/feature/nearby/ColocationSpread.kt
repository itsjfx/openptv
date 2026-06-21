package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Issue #172: PTV gives different modes serving one physical station identical coordinates — the
 * Richmond train stop and the V/Line "Richmond Railway Station" are both `stop_id 1162` at the same
 * lat/lng. Drawn as-is their map dots stack on a single pixel, so only the top one is visible and
 * tappable.
 *
 * [spreadColocatedStops] resolves each stop to the coordinate it should actually be drawn at: a stop
 * with no neighbour on its point keeps its exact coordinate; a group sharing a point is fanned evenly
 * around a small circle. Slot order within a group is sorted by route type then stop id, so a stop's
 * nudged position is deterministic and never jitters between renders (the train dot always sits in
 * the same place relative to its V/Line twin). [MapLibreOpenPtvMap] uses this for both rendering and
 * the tap hit-test, so a fanned-out dot is selectable exactly where it's shown.
 *
 * Grouping is expected to run on the already-filtered stop list, so only stops that genuinely overlap
 * under the current route-type filters are nudged — a lone V/Line stop (train filtered off) stays on
 * its true coordinate.
 */
internal fun spreadColocatedStops(stops: List<Stop>): List<Pair<Stop, Coordinates>> {
    val byLocation = stops.groupBy { colocationKey(it.latitude, it.longitude) }
    return stops.map { stop ->
        val group = byLocation.getValue(colocationKey(stop.latitude, stop.longitude))
        stop to displayCoordinate(stop, group)
    }
}

/**
 * Where [stop] should be drawn given the [group] of stops sharing its point. The longitude offset is
 * divided by cos(latitude) so the nudge stays roughly circular on the ground rather than stretched
 * east-west at Melbourne's latitude.
 */
private fun displayCoordinate(
    stop: Stop,
    group: List<Stop>,
): Coordinates {
    if (group.size <= 1) return Coordinates(lat = stop.latitude, lng = stop.longitude)
    val ordered = group.sortedWith(compareBy({ it.routeType.toCode() }, { it.id.value }))
    val index = ordered.indexOf(stop)
    val angle = 2.0 * PI * index / ordered.size
    val latitudeOffset = COLOCATION_OFFSET_DEG * sin(angle)
    val longitudeOffset =
        COLOCATION_OFFSET_DEG * cos(angle) / cos(stop.latitude * PI / DEGREES_PER_RADIAN)
    return Coordinates(
        lat = stop.latitude + latitudeOffset,
        lng = stop.longitude + longitudeOffset,
    )
}

/**
 * Bucket key for co-location detection: round lat/lng onto [COLOCATION_GRID] so stops on the same
 * (or all-but-identical) point hash together.
 */
private fun colocationKey(
    latitude: Double,
    longitude: Double,
): Pair<Long, Long> =
    (latitude * COLOCATION_GRID).roundToLong() to (longitude * COLOCATION_GRID).roundToLong()

// Grid for grouping stops that share a coordinate. 1e5 → ~1.1 m cells, so exact and near-exact
// duplicates land in the same bucket without false-merging genuinely distinct neighbouring stops.
private const val COLOCATION_GRID: Double = 1e5

// Radius each co-located dot is nudged from the shared point. ~20 m ≈ one pin diameter of
// separation at street zoom; small enough that the stop isn't meaningfully misplaced.
private const val COLOCATION_OFFSET_METERS: Double = 20.0
private const val METERS_PER_DEGREE_LAT: Double = 111_320.0
private const val COLOCATION_OFFSET_DEG: Double = COLOCATION_OFFSET_METERS / METERS_PER_DEGREE_LAT
private const val DEGREES_PER_RADIAN: Double = 180.0
