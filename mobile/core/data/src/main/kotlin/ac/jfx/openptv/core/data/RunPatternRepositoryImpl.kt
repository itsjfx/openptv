package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.network.RunPatternDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default impl. Same polling shape as [DepartureRepositoryImpl]: a cold `flow {}` that emits
 * `Loading` → fetch result → `delay(POLL_INTERVAL)` → loop, running in the collector's coroutine
 * so cancellation (the screen leaving RESUMED) tears the loop down cleanly. An error mid-poll
 * surfaces as [Result.Error] without breaking the loop — the next tick can recover.
 *
 * 30 s cadence matches the departures poll per the phase doc ("don't poll faster — wastes
 * battery and your PTV quota; backend caches at 15 s anyway").
 */
internal class RunPatternRepositoryImpl
    @Inject
    constructor(
        private val dataSource: RunPatternDataSource,
    ) : RunPatternRepository {
        override fun observeRunPattern(
            runRef: RunRef,
            routeType: RouteType,
        ): Flow<Result<RunPattern>> =
            flow {
                while (true) {
                    emit(Result.Loading)
                    emit(fetchOnce(runRef, routeType))
                    delay(POLL_INTERVAL)
                }
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun fetchOnce(
            runRef: RunRef,
            routeType: RouteType,
        ): Result<RunPattern> =
            try {
                Result.Success(dataSource.getRunPattern(runRef, routeType))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        private companion object {
            private val POLL_INTERVAL: Duration = 30.seconds
        }
    }
