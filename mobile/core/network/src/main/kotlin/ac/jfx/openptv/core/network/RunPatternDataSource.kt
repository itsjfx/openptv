package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import kotlinx.datetime.Instant

/**
 * Network boundary for a run's stopping pattern (issue #132). Throws on transport / parse
 * failures — `:core:data` owns the `Result` wrapping, same contract as [DepartureDataSource].
 *
 * [dateUtc] picks which calendar day's instance of the run to resolve — timetable `run_ref`s
 * recur daily, so a ref alone is ambiguous (issue #211). Null means "today's instance", which
 * is what a live run detail screen wants.
 */
interface RunPatternDataSource {
    suspend fun getRunPattern(
        runRef: RunRef,
        routeType: RouteType,
        dateUtc: Instant? = null,
    ): RunPattern
}
