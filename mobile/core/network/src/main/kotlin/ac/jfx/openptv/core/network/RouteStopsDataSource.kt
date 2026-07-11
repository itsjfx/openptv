package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId

/**
 * Network boundary for a route's stop ordering along one direction of travel (issue #204). The
 * journey planner uses the returned `stopId → sequence` map to decide whether a departure at
 * the origin is heading *towards* the destination (`seq(origin) < seq(destination)`) before it
 * spends a pattern fetch confirming the run actually calls there.
 *
 * Returns an empty map when the direction doesn't apply to the route — PTV reports
 * `stop_sequence: 0` for every stop in that case and the mapper drops them.
 *
 * Throws on transport / parse failures — `:core:data` owns the `Result` wrapping, same contract
 * as [DepartureDataSource].
 */
interface RouteStopsDataSource {
    suspend fun getStopSequences(
        routeId: RouteId,
        routeType: RouteType,
        directionId: DirectionId,
    ): Map<StopId, Int>
}
