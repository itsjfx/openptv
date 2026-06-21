package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Instant
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
        private val oneShotQueue: ArrayDeque<Result<DeparturesAtStop>> = ArrayDeque()

        private val observedFlow = MutableSharedFlow<Result<List<Departure>>>(replay = 1)
        val observedKeys: MutableList<Pair<StopId, RouteType>> = mutableListOf()
        val oneShotKeys: MutableList<Pair<StopId, RouteType>> = mutableListOf()

        /**
         * Time anchors (issue #182) seen by the last `getDepartures` / `observeDepartures` call.
         * `null` means the caller asked for live "now"; a non-null instant means a custom time was
         * threaded through. Feature tests assert these to prove the selected-time state reaches the
         * repository seam without clobbering.
         */
        var lastOneShotAt: Instant? = null
            private set
        var lastObservedAt: Instant? = null
            private set

        // -------- one-shot --------

        fun enqueueResult(result: Result<DeparturesAtStop>) {
            oneShotQueue.addLast(result)
        }

        /**
         * Convenience: enqueue a successful one-shot with [departures] and no sideloaded routes.
         * Tests that want to assert the route-name join on the favourites badge use
         * [enqueueSuccessWithRoutes] instead.
         */
        fun enqueueSuccess(departures: List<Departure>) {
            oneShotQueue.addLast(Result.Success(DeparturesAtStop(departures = departures, routes = emptyList())))
        }

        fun enqueueSuccessWithRoutes(
            departures: List<Departure>,
            routes: List<Route>,
        ) {
            oneShotQueue.addLast(Result.Success(DeparturesAtStop(departures = departures, routes = routes)))
        }

        fun enqueueError(throwable: Throwable) {
            oneShotQueue.addLast(Result.Error(throwable))
        }

        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
            at: Instant?,
        ): Result<DeparturesAtStop> {
            oneShotKeys += stopId to routeType
            lastOneShotAt = at
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
            at: Instant?,
        ): Flow<Result<List<Departure>>> {
            observedKeys += stopId to routeType
            lastObservedAt = at
            return observedFlow.asSharedFlow()
        }

        // -------- paging --------

        private val loadMoreQueue: ArrayDeque<Result<List<Departure>>> = ArrayDeque()
        val loadMoreCalls: MutableList<LoadMoreCall> = mutableListOf()

        data class LoadMoreCall(
            val stopId: StopId,
            val routeType: RouteType,
            val after: Instant,
            val maxResults: Int,
        )

        fun enqueueLoadMoreResult(result: Result<List<Departure>>) {
            loadMoreQueue.addLast(result)
        }

        fun enqueueLoadMoreSuccess(departures: List<Departure>) {
            loadMoreQueue.addLast(Result.Success(departures))
        }

        fun enqueueLoadMoreError(throwable: Throwable) {
            loadMoreQueue.addLast(Result.Error(throwable))
        }

        override suspend fun loadMore(
            stopId: StopId,
            routeType: RouteType,
            after: Instant,
            maxResults: Int,
        ): Result<List<Departure>> {
            loadMoreCalls += LoadMoreCall(stopId, routeType, after, maxResults)
            // Default to an empty success so feature tests that don't care about paging stay
            // brief — they enqueue when they want to drive a specific page.
            return loadMoreQueue.removeFirstOrNull() ?: Result.Success(emptyList())
        }
    }
