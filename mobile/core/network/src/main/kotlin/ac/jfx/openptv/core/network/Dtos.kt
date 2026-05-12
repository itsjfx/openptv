package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for `GET /api/v3/search/{term}`. Marked `internal` so DTOs never leak past the
 * network boundary; the rest of the app sees domain models from `core.model`.
 *
 * The PTV search response also carries `routes`, `outlets`, and a `status` block — Phase 02
 * only renders stops, so those fields are ignored. `Json { ignoreUnknownKeys = true }` in the
 * Hilt module means we don't have to mirror them here.
 */
@Serializable
internal data class SearchResponseDto(
    val stops: List<StopDto> = emptyList(),
)

@Serializable
internal data class StopDto(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_name") val stopName: String,
    @SerialName("stop_suburb") val stopSuburb: String = "",
    @SerialName("route_type") val routeType: Int,
    @SerialName("stop_latitude") val stopLatitude: Double = 0.0,
    @SerialName("stop_longitude") val stopLongitude: Double = 0.0,
)

/**
 * PTV occasionally emits stop names with trailing whitespace (e.g. `"20 Matthew Flinders Ave "`).
 * Trim once here so every consumer sees clean strings.
 *
 * `internal` plus the `:core:data` consumer call site means this is a friend-of-the-module API,
 * not a public API. Consumers in other modules go through the repository, not the mapper.
 */
internal fun StopDto.toDomain(): Stop = Stop(
    id = StopId(stopId),
    name = stopName.trim(),
    suburb = stopSuburb.trim(),
    routeType = RouteType.fromCode(routeType),
    latitude = stopLatitude,
    longitude = stopLongitude,
)

internal fun SearchResponseDto.toDomain(): List<Stop> = stops.map { it.toDomain() }
