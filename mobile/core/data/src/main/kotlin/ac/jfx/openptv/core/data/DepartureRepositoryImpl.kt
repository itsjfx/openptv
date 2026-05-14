package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.DepartureDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default impl. The one-shot path mirrors [StopSearchRepositoryImpl]; the polling Flow is the
 * interesting bit:
 *
 * ```
 * collector.subscribe
 *   │
 *   ▼
 *   emit Result.Loading
 *   │
 *   ▼
 *   fetch ── success ──► emit Result.Success(list)
 *     │
 *     └── failure ─────► emit Result.Error(throwable)
 *   │
 *   ▼
 *   delay(POLL_INTERVAL)
 *   │
 *   ▼
 *   (loop)
 *   │
 *   ▼ collector cancels
 *   delay / fetch cancellation propagates; the flow{} body exits cleanly.
 * ```
 *
 * Cancellation contract: `flow {}` runs in the collector's coroutine, so when the caller's
 * scope is cancelled (e.g. `repeatOnLifecycle` tearing down) the in-flight `delay` /
 * suspending `fetch` cancels and the loop exits. No `WhileSubscribed` `stateIn` is needed
 * here — the caller owns lifecycle semantics. Tests pin this with virtual time.
 *
 * Polling cadence is 30 s, matching the phase doc ("don't poll faster — wastes battery and
 * your PTV quota; backend caches at 15 s anyway"). It's a `private const val` so a future ADR
 * can adjust it in one place.
 */
internal class DepartureRepositoryImpl
    @Inject
    constructor(
        private val dataSource: DepartureDataSource,
    ) : DepartureRepository {
        @Suppress("TooGenericExceptionCaught")
        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Result<List<Departure>> =
            try {
                Result.Success(dataSource.getDepartures(stopId, routeType))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        override fun observeDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Flow<Result<List<Departure>>> =
            flow {
                while (true) {
                    emit(Result.Loading)
                    emit(fetchOnce(stopId, routeType))
                    delay(POLL_INTERVAL)
                }
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun fetchOnce(
            stopId: StopId,
            routeType: RouteType,
        ): Result<List<Departure>> =
            try {
                Result.Success(dataSource.getDepartures(stopId, routeType))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        private companion object {
            private val POLL_INTERVAL: Duration = 30.seconds
        }
    }
