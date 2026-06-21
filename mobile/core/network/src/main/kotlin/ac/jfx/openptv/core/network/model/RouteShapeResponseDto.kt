package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteShape
import ac.jfx.openptv.core.model.StopId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for `GET /api/v3/stops/route/{route_id}/route_type/{route_type}?include_geopath=true`
 * (issue #187). PTV returns the route's stops (each with lat/lng) plus a top-level `geopath` array
 * — one entry per direction of travel, each carrying the polyline as a list of `paths` strings.
 *
 * Internal so the wire shape never escapes `:core:network`; [toDomain] is the only thing
 * `:core:data` calls.
 *
 * **Why this endpoint and not the pattern endpoint.** The run-pattern endpoint
 * (`/v3/pattern/run/...`) returns `geopath: null` even with `include_geopath=true` (verified live
 * for trains + buses), and its stop sideload omits geo fields. This endpoint reliably returns
 * both, so the data layer fetches it as a companion call and joins onto the run's pattern.
 */
@Serializable
internal data class RouteShapeResponseDto(
    @SerialName("stops") val stops: List<RouteStopDto> = emptyList(),
    @SerialName("geopath") val geopath: List<GeopathSegmentDto> = emptyList(),
)

/**
 * A stop on the route, projected to just the identity + location fields the run-pattern map join
 * needs. The route endpoint nests several other fields (disruptions, ticketing, interchange) we
 * don't use here.
 */
@Serializable
internal data class RouteStopDto(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_latitude") val stopLatitude: Double = 0.0,
    @SerialName("stop_longitude") val stopLongitude: Double = 0.0,
)

/**
 * One geopath entry: the route line for a single direction of travel. PTV encodes the polyline as
 * a list of `paths` strings, each a whitespace-separated run of `"lat, lng"` pairs — e.g.
 * `"-37.8267, 145.0582 -37.8267, 145.0583 ..."`. A route shape can contain several `paths` strings
 * (disjoint or branching segments); each is parsed independently into one [List] of [Coordinates].
 *
 * `valid_from` / `valid_to` are present in the wire shape (the active timetable window for the
 * geometry) but we don't filter on them — PTV already returns the current geometry by default, and
 * a stale shape is still a better map than no map.
 */
@Serializable
internal data class GeopathSegmentDto(
    @SerialName("direction_id") val directionId: Int = -1,
    @SerialName("paths") val paths: List<String> = emptyList(),
)

/**
 * Map the route-shape envelope to [RouteShape]. Each `paths` string is parsed into a polyline; a
 * coordinate pair that fails to parse (malformed token) is skipped rather than failing the whole
 * fetch — a partial line is still useful and the timeline never depends on geometry. Empty
 * polylines (no parseable points) are dropped so the consumer's "is there a line?" check is honest.
 */
internal fun RouteShapeResponseDto.toDomain(): RouteShape {
    val geopathByDirection: Map<Int, List<List<Coordinates>>> =
        geopath
            .associate { segment ->
                segment.directionId to
                    segment.paths
                        .map { parsePolyline(it) }
                        .filter { it.isNotEmpty() }
            }
            .filterValues { it.isNotEmpty() }

    val stopCoordinates: Map<StopId, Coordinates> =
        stops.associate { dto ->
            StopId(dto.stopId) to Coordinates(lat = dto.stopLatitude, lng = dto.stopLongitude)
        }

    return RouteShape(
        geopathByDirection = geopathByDirection,
        stopCoordinates = stopCoordinates,
    )
}

/**
 * Parse one PTV geopath `paths` string into an ordered polyline. The string is whitespace-separated
 * `"lat, lng"` pairs; the comma binds tighter than the space (`"-37.82, 145.05 -37.83, 145.06"`).
 * Tokenising on whitespace yields `lat,` then `lng` alternately, so we walk the tokens two at a
 * time, stripping the trailing comma off the latitude. Any pair that doesn't parse as two doubles
 * is skipped — defensive against the odd malformed token without dropping the whole line.
 */
private fun parsePolyline(raw: String): List<Coordinates> {
    val tokens = raw.trim().split(WHITESPACE).filter { it.isNotBlank() }
    val points = ArrayList<Coordinates>(tokens.size / 2)
    var i = 0
    while (i + 1 < tokens.size) {
        val lat = tokens[i].removeSuffix(",").toDoubleOrNull()
        val lng = tokens[i + 1].removeSuffix(",").toDoubleOrNull()
        if (lat != null && lng != null) {
            points += Coordinates(lat = lat, lng = lng)
        }
        i += 2
    }
    return points
}

private val WHITESPACE = "\\s+".toRegex()
