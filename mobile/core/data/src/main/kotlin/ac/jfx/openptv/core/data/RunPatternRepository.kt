package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import kotlinx.coroutines.flow.Flow

/**
 * Run-pattern read path (issue #132). Network is the single source of truth — patterns are
 * ephemeral live data, same category as departures, so nothing is persisted.
 */
interface RunPatternRepository {
    /**
     * Polling Flow of the run's stopping pattern. Emits [Result.Loading] before each fetch,
     * then `Success` / `Error`, then re-fetches on the same 30 s cadence as
     * [DepartureRepository.observeDepartures]. Collector lifetime drives the loop — cancelling
     * the collector stops the polling.
     */
    fun observeRunPattern(
        runRef: RunRef,
        routeType: RouteType,
    ): Flow<Result<RunPattern>>
}
