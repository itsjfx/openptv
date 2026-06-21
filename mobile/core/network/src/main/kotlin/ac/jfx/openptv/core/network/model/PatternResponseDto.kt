package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.PlatformNumber
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunPatternStop
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for `GET /api/v3/pattern/run/{run_ref}/route_type/{route_type}` with
 * `expand=Stop,Route,Direction`. PTV returns one `departures` entry per stop on the run's
 * pattern (chronological), alongside sideloaded `stops`, `routes` and `directions` maps the
 * mapper joins client-side — same envelope convention as [DeparturesResponseDto].
 *
 * Internal so the wire shape never escapes `:core:network`; [toDomain] is the only thing
 * `:core:data` calls. [RouteSideloadDto] and [DirectionDto] are shared with the departures
 * envelope — PTV emits the identical sideload shape on both endpoints.
 */
@Serializable
internal data class PatternResponseDto(
    @SerialName("departures") val departures: List<PatternDepartureDto> = emptyList(),
    @SerialName("stops") val stops: Map<String, PatternStopDto> = emptyMap(),
    @SerialName("routes") val routes: Map<String, RouteSideloadDto> = emptyMap(),
    @SerialName("directions") val directions: Map<String, DirectionDto> = emptyMap(),
)

@Serializable
internal data class PatternDepartureDto(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("route_id") val routeId: Int,
    @SerialName("direction_id") val directionId: Int,
    @SerialName("scheduled_departure_utc") val scheduledDepartureUtc: String,
    @SerialName("estimated_departure_utc") val estimatedDepartureUtc: String? = null,
    @SerialName("platform_number") val platformNumber: String? = null,
)

/**
 * Sideload row under the `stops` map. Distinct from the stop shapes used by search / nearby —
 * the pattern endpoint nests the geo fields and we don't need them, so this projection keeps
 * just the display fields.
 */
@Serializable
internal data class PatternStopDto(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_name") val stopName: String = "",
    @SerialName("stop_suburb") val stopSuburb: String = "",
)

/**
 * Map the PTV envelope into a [RunPattern]. The `stops` / `routes` / `directions` maps are keyed
 * by stringified ids; each pattern row joins its `stop_id` to the stop sideload for the display
 * name (falling back to `#<stopId>` if PTV omits the entry — defensive, should never happen with
 * `expand=Stop`). The run-level `route` and `directionName` come from the first row's ids since
 * every row on a single run shares them.
 */
internal fun PatternResponseDto.toDomain(): RunPattern {
    val first = departures.firstOrNull()
    val route =
        first?.let { d ->
            routes[d.routeId.toString()]?.let { dto ->
                Route(
                    id = RouteId(d.routeId),
                    number = dto.routeNumber.trim(),
                    name = dto.routeName.trim(),
                    routeType = RouteType.fromCode(dto.routeType),
                )
            }
        }
    val directionName =
        first?.let { directions[it.directionId.toString()]?.directionName?.trim() }.orEmpty()
    return RunPattern(
        route = route,
        directionName = directionName,
        // Captured from the first row so the data layer can pick the matching geopath segment from
        // the `stops/route` endpoint (issue #187) — every row on one run shares route + direction.
        directionId = first?.directionId,
        // Geopath is intentionally left empty here: the pattern endpoint returns `geopath: null`
        // even with `include_geopath=true`, so the data layer fills it from a companion fetch.
        stops =
            departures.map { d ->
                val stopDto = stops[d.stopId.toString()]
                RunPatternStop(
                    stopId = StopId(d.stopId),
                    stopName = stopDto?.stopName?.trim().orEmpty().ifBlank { "#${d.stopId}" },
                    stopSuburb = stopDto?.stopSuburb?.trim().orEmpty(),
                    scheduledDepartureUtc = Instant.parse(d.scheduledDepartureUtc),
                    estimatedDepartureUtc = d.estimatedDepartureUtc?.let(Instant::parse),
                    platform = d.platformNumber?.takeIf { it.isNotBlank() }?.let(::PlatformNumber),
                )
            },
    )
}
