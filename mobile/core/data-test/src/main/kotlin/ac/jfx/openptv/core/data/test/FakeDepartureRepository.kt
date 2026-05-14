package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [DepartureRepository] that's friendly to both unit tests and Hilt-
 * instrumented UI tests.
 *
 *  - [getDepartures] dequeues a one-shot [Result] enqueued via [enqueueResult] (or the
 *    convenience helpers); empty queue means "you forgot to seed me" and fails loud.
 *  - [observeDepartures] returns the inner [MutableSharedFlow] cast to `Flow`. Tests drive the
 *    Flow with [emit] / the convenience helpers. Real polling isn't simulated — tests that need
 *    timing exercise the production impl in `:core:data`.
 *
 * `@Singleton` so a `setUp()` emission lands on the same instance the ViewModel ends up
 * collecting. `replay = 1` so a late subscriber sees the most recent value — matches what
 * `StateFlow` would give a feature test, without forcing the fake to advertise a `null` initial.
 */
@Singleton
class FakeDepartureRepository
    @Inject
    constructor() : DepartureRepository {
        private val oneShotQueue: ArrayDeque<Result<List<Departure>>> = ArrayDeque()

        private val observedFlow = MutableSharedFlow<Result<List<Departure>>>(replay = 1)
        val observedKeys: MutableList<Pair<StopId, RouteType>> = mutableListOf()
        val oneShotKeys: MutableList<Pair<StopId, RouteType>> = mutableListOf()

        // -------- one-shot --------

        fun enqueueResult(result: Result<List<Departure>>) {
            oneShotQueue.addLast(result)
        }

        fun enqueueSuccess(departures: List<Departure>) {
            oneShotQueue.addLast(Result.Success(departures))
        }

        fun enqueueError(throwable: Throwable) {
            oneShotQueue.addLast(Result.Error(throwable))
        }

        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Result<List<Departure>> {
            oneShotKeys += stopId to routeType
            return oneShotQueue.removeFirstOrNull()
                ?: error("FakeDepartureRepository: no result enqueued for ($stopId, $routeType)")
        }

        // -------- streamed --------

        /** Emit a value onto the observed flow. Use to drive tick simulation in feature tests. */
        suspend fun emit(result: Result<List<Departure>>) {
            observedFlow.emit(result)
        }

        suspend fun emitLoading() {
            observedFlow.emit(Result.Loading)
        }

        suspend fun emitSuccess(departures: List<Departure>) {
            observedFlow.emit(Result.Success(departures))
        }

        suspend fun emitError(throwable: Throwable) {
            observedFlow.emit(Result.Error(throwable))
        }

        override fun observeDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Flow<Result<List<Departure>>> {
            observedKeys += stopId to routeType
            return observedFlow.asSharedFlow()
        }
    }
