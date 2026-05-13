package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId

/**
 * Network-layer fetch for live [Departure]s at a stop. The polling cadence (30 s) lives in
 * `:core:data` — this interface is one-shot. If the proxy ever exposes a server-push channel
 * (SSE / WebSocket), a second implementation slots in without touching the data layer.
 */
interface DepartureDataSource {
    suspend fun getDepartures(
        stopId: StopId,
        routeType: RouteType,
    ): List<Departure>
}
