package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.Disruption
import ac.jfx.openptv.core.model.DisruptionId
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
    /**
     * PTV sideloads the full disruption records here (with `expand=Disruption`), keyed by the
     * stringified `disruption_id`. Each departure references them by id via [DepartureDto.disruptionIds];
     * the mapper joins the two so the domain [Departure] carries its disruptions inline (issue #177).
     */
    @SerialName("disruptions") val disruptions: Map<String, DisruptionDto> = emptyMap(),
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
 * Sideload row for a single disruption under the response's `disruptions` map. Carries the public
 * fields the stop-detail sheet renders (issue #177); the noisier fields (`from_date`, `routes`,
 * `display_on_board`, …) are dropped here because `Json { ignoreUnknownKeys = true }` lets us pick
 * only what the UI needs. Mapped to the shared domain [Disruption] via [toDomain].
 */
@Serializable
internal data class DisruptionDto(
    @SerialName("disruption_id") val disruptionId: Long,
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("disruption_type") val disruptionType: String = "",
    @SerialName("url") val url: String? = null,
    @SerialName("colour") val colour: String? = null,
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
internal fun DeparturesResponseDto.toDomain(): DeparturesAtStop {
    // Index the sideloaded disruption records by id so each departure's `disruption_ids` resolve in
    // O(1). PTV keys the map by the stringified id; we re-key by the numeric id the departure rows
    // carry. Records the departure references but the sideload omits are dropped (mapNotNull below).
    val disruptionsById = disruptions.values.associateBy({ it.disruptionId }, { it.toDomain() })
    return DeparturesAtStop(
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
                    disruptions = d.disruptionIds.mapNotNull { disruptionsById[it] },
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
}

/**
 * Map a sideloaded [DisruptionDto] into the domain [Disruption]. Title/description/type are trimmed
 * at the boundary like every other PTV string; `url`/`colour` are passed through verbatim (the UI
 * decides whether to render the link / accent).
 */
internal fun DisruptionDto.toDomain(): Disruption =
    Disruption(
        id = DisruptionId(disruptionId),
        title = title.trim(),
        description = description.trim(),
        type = disruptionType.trim(),
        url = url?.takeIf { it.isNotBlank() },
        colour = colour?.takeIf { it.isNotBlank() },
    )
