package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.StopId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for `GET /api/v3/stops/route/{route_id}/route_type/{route_type}?direction_id={d}`
 * (issue #204). With a `direction_id`, PTV fills each stop's `stop_sequence` with its 1-based
 * position along that direction of travel; without one (or for a direction that doesn't apply
 * to the route) every `stop_sequence` comes back 0 — verified live, so the mapper treats 0 as
 * "unordered" and omits the stop from the sequence map.
 *
 * This is the same endpoint [RouteShapeResponseDto] parses, projected differently: that DTO
 * keeps lat/lng for the map join and ignores sequence; this one keeps sequence for the journey
 * planner's direction-of-travel check and ignores geography. Two slim projections beat one DTO
 * that drags both concerns everywhere.
 */
@Serializable
internal data class RouteStopsResponseDto(
    @SerialName("stops") val stops: List<RouteStopSequenceDto> = emptyList(),
)

@Serializable
internal data class RouteStopSequenceDto(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_sequence") val stopSequence: Int = 0,
)

/**
 * Project to a `stopId → sequence` map, dropping unordered (`stop_sequence == 0`) entries so an
 * inapplicable direction yields an empty map — the caller's "does this direction serve these
 * stops in this order?" check then fails naturally.
 */
internal fun RouteStopsResponseDto.toSequenceMap(): Map<StopId, Int> =
    stops
        .filter { it.stopSequence > 0 }
        .associate { StopId(it.stopId) to it.stopSequence }
