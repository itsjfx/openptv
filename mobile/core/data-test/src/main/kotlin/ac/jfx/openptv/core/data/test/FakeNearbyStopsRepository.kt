package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.NearbyStopsRepository
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [NearbyStopsRepository]. Mirrors [FakeStopSearchRepository]: enqueue
 * results with [enqueueResult] (or [enqueueSuccess] / [enqueueError]); each call to [stopsNear]
 * dequeues the next one. An empty queue returns an empty Success — never throws, never blocks.
 *
 * [requestedCalls] records every call so feature tests can assert on the camera-idle fetch
 * sequence (it's the load-bearing surface for the debounce assertions in
 * `NearbyViewModelTest`).
 *
 * `@Singleton` so the same instance backs every consumer in a single test — otherwise an
 * `enqueueSuccess` from `setUp()` would land on a different instance than the ViewModel ends up
 * using.
 */
@Singleton
class FakeNearbyStopsRepository
    @Inject
    constructor() : NearbyStopsRepository {
        private val queue: ArrayDeque<Result<List<Stop>>> = ArrayDeque()

        /** Record of every [stopsNear] call. The list is mutated, so snapshot before assertions. */
        val requestedCalls: MutableList<Request> = mutableListOf()

        data class Request(
            val coordinates: Coordinates,
            val radiusMeters: Int,
        )

        fun enqueueResult(result: Result<List<Stop>>) {
            queue.addLast(result)
        }

        fun enqueueSuccess(stops: List<Stop>) {
            queue.addLast(Result.Success(stops))
        }

        fun enqueueError(throwable: Throwable) {
            queue.addLast(Result.Error(throwable))
        }

        override suspend fun stopsNear(
            coordinates: Coordinates,
            radiusMeters: Int,
        ): Result<List<Stop>> {
            requestedCalls += Request(coordinates, radiusMeters)
            return queue.removeFirstOrNull() ?: Result.Success(emptyList())
        }
    }
