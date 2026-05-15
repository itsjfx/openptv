package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for `GET /api/v3/stops/{stop_id}/route_type/{route_type}`. PTV wraps the serving
 * routes array **inside** the `stop` object — not at the top level as an earlier read of the spec
 * suggested. `Json { ignoreUnknownKeys = true }` in `NetworkModule` means new keys won't break
 * parsing.
 *
 * Internal because `:core:data` should never see the wire shape — it consumes [toDomain] only.
 */
@Serializable
internal data class StopResponseDto(
    @SerialName("stop") val stop: StopDetailsDto? = null,
)

@Serializable
internal data class StopDetailsDto(
    @SerialName("stop_id") val stopId: Int,
    @SerialName("stop_name") val stopName: String,
    @SerialName("stop_suburb") val stopSuburb: String = "",
    @SerialName("route_type") val routeType: Int,
    @SerialName("stop_latitude") val stopLatitude: Double = 0.0,
    @SerialName("stop_longitude") val stopLongitude: Double = 0.0,
    @SerialName("routes") val routes: List<RouteDto> = emptyList(),
)

@Serializable
internal data class RouteDto(
    @SerialName("route_id") val routeId: Int,
    @SerialName("route_name") val routeName: String,
    @SerialName("route_number") val routeNumber: String = "",
    @SerialName("route_type") val routeType: Int,
)

/**
 * Map the PTV wire envelope into the domain [StopDetail]. Returns `null` when the response has
 * no stop block — the repository surfaces that as `Result.Error` so the UI can show "stop not
 * found" rather than rendering a half-empty header.
 */
internal fun StopResponseDto.toDomain(): StopDetail? {
    val s = stop ?: return null
    return StopDetail(
        stop =
            Stop(
                id = StopId(s.stopId),
                name = s.stopName.trim(),
                suburb = s.stopSuburb.trim(),
                routeType = RouteType.fromCode(s.routeType),
                latitude = s.stopLatitude,
                longitude = s.stopLongitude,
            ),
        servingRoutes = s.routes.map { it.toDomain() },
    )
}

internal fun RouteDto.toDomain(): Route =
    Route(
        id = RouteId(routeId),
        number = routeNumber.trim(),
        name = routeName.trim(),
        routeType = RouteType.fromCode(routeType),
    )
