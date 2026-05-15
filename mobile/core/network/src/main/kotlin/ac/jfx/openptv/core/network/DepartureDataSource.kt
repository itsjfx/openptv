package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Network-layer fetch for live [Departure]s at a stop. The polling cadence (30 s) lives in
 * `:core:data` — this interface is one-shot. If the proxy ever exposes a server-push channel
 * (SSE / WebSocket), a second implementation slots in without touching the data layer.
 *
 * [dateUtc], [maxResults], and [lookBackwards] are optional pass-throughs to the PTV API:
 *  - `date_utc` shifts the window forward in time so we can ask for "departures from this instant
 *    onward". Without it PTV anchors the response at the **start of the current calendar day**,
 *    which is why the original implementation needed a client-side `isDeparted` filter.
 *  - `look_backwards` is honoured by PTV alongside `date_utc`: setting it to `false` makes the
 *    response exclude entries scheduled before `date_utc`. Server-side filtering replaces the
 *    client-side filter — see issue #86.
 *  - `max_results` is per-route (PTV applies it once `route_id` is omitted), so a stop with three
 *    routes serving it returns up to `3 * max_results` departures.
 */
interface DepartureDataSource {
    suspend fun getDepartures(
        stopId: StopId,
        routeType: RouteType,
        dateUtc: Instant? = null,
        maxResults: Int? = null,
        lookBackwards: Boolean? = null,
    ): List<Departure>
}
