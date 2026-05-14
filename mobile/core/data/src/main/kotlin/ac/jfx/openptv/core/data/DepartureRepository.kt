package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow

/**
 * Repository for live [Departure]s at a stop. Two surfaces:
 *
 *  - [getDepartures] — one-shot fetch, useful for pull-to-refresh and for the Glance widget
 *    (which doesn't keep a long-lived collector around).
 *  - [observeDepartures] — a hot-ish `Flow<Result<List<Departure>>>` that re-emits on a 30 s
 *    tick. Collector lifetime drives the polling loop: cancelling the collector cancels the
 *    underlying `delay` and `fetch`, no orphan coroutines. The Flow emits `Result.Loading`
 *    immediately on subscribe and on each subsequent refresh, then the fetched result.
 *
 * Both surfaces fold non-cancellation throwables into [Result.Error]; cancellation propagates.
 *
 * Errors mid-poll do not break the loop — the next tick recovers naturally. Tests pin that
 * behaviour in `DepartureRepositoryImplTest`.
 */
interface DepartureRepository {
    suspend fun getDepartures(
        stopId: StopId,
        routeType: RouteType,
    ): Result<List<Departure>>

    fun observeDepartures(
        stopId: StopId,
        routeType: RouteType,
    ): Flow<Result<List<Departure>>>
}
