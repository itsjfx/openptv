package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef

/**
 * Network boundary for a run's stopping pattern (issue #132). Throws on transport / parse
 * failures — `:core:data` owns the `Result` wrapping, same contract as [DepartureDataSource].
 */
interface RunPatternDataSource {
    suspend fun getRunPattern(
        runRef: RunRef,
        routeType: RouteType,
    ): RunPattern
}
