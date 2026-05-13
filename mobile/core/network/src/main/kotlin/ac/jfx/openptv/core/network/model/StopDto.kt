package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for an entry in `SearchResponseDto.stops`. Marked `internal` so DTOs never leak
 * past the network boundary; the rest of the app sees `Stop` from `core.model`.
 */
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
