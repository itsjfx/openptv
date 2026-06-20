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
    /**
     * Top-level lat/lon — PTV's `/stops/location/...` (nearby) endpoint puts the coordinate
     * here. The single-stop endpoint (`/stops/{id}/route_type/{type}?stop_location=true`) uses
     * the nested [stopLocation] shape instead. Defaulted to 0.0 so we don't fail parsing when
     * only one of the two shapes is present — [toDomain] picks the non-zero one.
     */
    @SerialName("stop_latitude") val stopLatitude: Double = 0.0,
    @SerialName("stop_longitude") val stopLongitude: Double = 0.0,
    /**
     * PR #139 follow-up: the `/stops/{id}/route_type/{type}?stop_location=true` endpoint
     * returns the GPS pair under `stop_location.gps.{latitude,longitude}` — NOT at the top
     * level. Without this field the lat/lon read in [toDomain] is always (0, 0), which is what
     * the issue #123 "show on map" affordance was hitting (PR #139 smoke regression: map
     * centred on the user's location instead of the requested stop).
     */
    @SerialName("stop_location") val stopLocation: StopLocationDto? = null,
    @SerialName("routes") val routes: List<RouteDto> = emptyList(),
)

@Serializable
internal data class StopLocationDto(
    @SerialName("suburb") val suburb: String = "",
    @SerialName("gps") val gps: GpsDto? = null,
)

@Serializable
internal data class GpsDto(
    @SerialName("latitude") val latitude: Double = 0.0,
    @SerialName("longitude") val longitude: Double = 0.0,
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
 *
 * [requestedRouteType] filters the serving-routes list to the mode the caller asked for. PTV's
 * `/stops/{id}/route_type/{type}` endpoint IGNORES the `route_type` path param for the `routes`
 * array and returns *every* route serving the physical `stop_id`. At co-located stops that share
 * a `stop_id` (Richmond is the canonical case: the metro platforms and the V/Line platform are
 * one `stop_id` 1162) this mixes modes, so a V/Line stop comes back carrying metro train routes
 * (issue #175 — the Nearby sheet rendered Alamein/Belgrave/… chips on the V/Line pin).
 * Departures are already fetched per `(stopId, routeType)` and are correct, so filtering the
 * routes here keeps `servingRoutes` consistent with the mode every caller requested.
 * [RouteType.Unknown] disables the filter (we never put Unknown on the wire — this only guards
 * against an unexpected upstream value, where dropping every route would be worse than passing
 * the raw list through).
 */
internal fun StopResponseDto.toDomain(requestedRouteType: RouteType): StopDetail? {
    val s = stop ?: return null
    // Pick the nested `stop_location.gps` pair when the top-level fields are absent (= default
    // 0.0) — the single-stop endpoint only populates the nested shape. The nearby endpoint
    // continues to use the top-level fields; this fallback is one-way (top-level wins when
    // both are present, because nearby's flat shape is the canonical projection we render).
    val gps = s.stopLocation?.gps
    val lat = if (s.stopLatitude != 0.0) s.stopLatitude else gps?.latitude ?: 0.0
    val lng = if (s.stopLongitude != 0.0) s.stopLongitude else gps?.longitude ?: 0.0
    // Same fallback for suburb — single-stop endpoint puts it under `stop_location.suburb`
    // rather than `stop_suburb`.
    val suburb = s.stopSuburb.ifBlank { s.stopLocation?.suburb ?: "" }
    return StopDetail(
        stop =
            Stop(
                id = StopId(s.stopId),
                name = s.stopName.trim(),
                suburb = suburb.trim(),
                routeType = RouteType.fromCode(s.routeType),
                latitude = lat,
                longitude = lng,
            ),
        servingRoutes =
            s.routes
                .map { it.toDomain() }
                .filter { requestedRouteType == RouteType.Unknown || it.routeType == requestedRouteType },
    )
}

internal fun RouteDto.toDomain(): Route =
    Route(
        id = RouteId(routeId),
        number = routeNumber.trim(),
        name = routeName.trim(),
        routeType = RouteType.fromCode(routeType),
    )
