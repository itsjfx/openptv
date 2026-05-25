package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DepartureFlags
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.PlatformNumber
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for `GET /api/v3/departures/route_type/{route_type}/stop/{stop_id}` with
 * `expand=Run,Direction,Route,Disruption`. PTV returns a flat list of `departures` alongside
 * sideloaded `directions` and `routes` maps that the mapper joins client-side.
 *
 * Internal so the wire shape never escapes `:core:network`. The mapper [toDomain] is the only
 * thing `:core:data` calls.
 */
@Serializable
internal data class DeparturesResponseDto(
    @SerialName("departures") val departures: List<DepartureDto> = emptyList(),
    @SerialName("directions") val directions: Map<String, DirectionDto> = emptyMap(),
    @SerialName("routes") val routes: Map<String, RouteSideloadDto> = emptyMap(),
)

@Serializable
internal data class DepartureDto(
    @SerialName("route_id") val routeId: Int,
    @SerialName("run_ref") val runRef: String,
    @SerialName("direction_id") val directionId: Int,
    @SerialName("scheduled_departure_utc") val scheduledDepartureUtc: String,
    @SerialName("estimated_departure_utc") val estimatedDepartureUtc: String? = null,
    @SerialName("platform_number") val platformNumber: String? = null,
    @SerialName("disruption_ids") val disruptionIds: List<Long> = emptyList(),
)

@Serializable
internal data class DirectionDto(
    @SerialName("direction_id") val directionId: Int,
    @SerialName("direction_name") val directionName: String,
)

/**
 * Sideload row for a single route under the `routes` map. Distinct from the [RouteDto] used by
 * the stop-detail endpoint because PTV emits a slightly different shape here (no `route_id`
 * field — the map key is the id) and the field defaults differ. Mapped via [toDomain] to the
 * shared domain [Route].
 */
@Serializable
internal data class RouteSideloadDto(
    @SerialName("route_name") val routeName: String = "",
    @SerialName("route_number") val routeNumber: String = "",
    @SerialName("route_type") val routeType: Int,
)

/**
 * Map the PTV envelope into [DeparturesAtStop] — both the [Departure] list and a list of [Route]
 * projections sideloaded under the response's `routes` map. The `directions` map is keyed by
 * stringified direction id; we look up each row's direction by id, falling back to a synthetic
 * "Unknown" entry if PTV omits the sideload (defensive — should never happen with
 * `expand=Direction`). The `routes` map keys are stringified route ids; the favourites screen
 * joins each departure's `routeId` back to a [Route] so it can render the line name on the badge
 * (issue #137 regression).
 */
internal fun DeparturesResponseDto.toDomain(): DeparturesAtStop =
    DeparturesAtStop(
        departures =
            departures.map { d ->
                val directionDto = directions[d.directionId.toString()]
                Departure(
                    routeId = RouteId(d.routeId),
                    runRef = RunRef(d.runRef),
                    scheduledDepartureUtc = Instant.parse(d.scheduledDepartureUtc),
                    estimatedDepartureUtc = d.estimatedDepartureUtc?.let(Instant::parse),
                    platform = d.platformNumber?.takeIf { it.isNotBlank() }?.let(::PlatformNumber),
                    direction =
                        Direction(
                            id = DirectionId(d.directionId),
                            name = directionDto?.directionName?.trim() ?: "",
                        ),
                    flags = DepartureFlags(hasDisruption = d.disruptionIds.isNotEmpty()),
                )
            },
        routes =
            routes.mapNotNull { (id, dto) ->
                id.toIntOrNull()?.let { routeId ->
                    Route(
                        id = RouteId(routeId),
                        number = dto.routeNumber.trim(),
                        name = dto.routeName.trim(),
                        routeType = RouteType.fromCode(dto.routeType),
                    )
                }
            },
    )
