package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopDetailRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [StopDetailRepository]. Enqueue results with [enqueueResult] (or
 * [enqueueSuccess] / [enqueueError]); each call to [getStopDetail] dequeues the next one. An
 * empty queue returns an empty Success of the canonical Flinders fixture — never throws, never
 * blocks. Mirrors [FakeStopSearchRepository]'s shape so feature androidTests get a consistent
 * surface across repos.
 *
 * `@Inject constructor` so Hilt builds it inside the test graph. `@Singleton` so a `setUp()`
 * enqueue lands on the same instance the ViewModel ends up consuming.
 */
@Singleton
class FakeStopDetailRepository
    @Inject
    constructor() : StopDetailRepository {
        private val queue: ArrayDeque<Result<StopDetail>> = ArrayDeque()
        val requestedKeys: MutableList<Pair<StopId, RouteType>> = mutableListOf()

        fun enqueueResult(result: Result<StopDetail>) {
            queue.addLast(result)
        }

        fun enqueueSuccess(detail: StopDetail) {
            queue.addLast(Result.Success(detail))
        }

        fun enqueueError(throwable: Throwable) {
            queue.addLast(Result.Error(throwable))
        }

        override suspend fun getStopDetail(
            stopId: StopId,
            routeType: RouteType,
        ): Result<StopDetail> {
            requestedKeys += stopId to routeType
            return queue.removeFirstOrNull()
                ?: error("FakeStopDetailRepository: no result enqueued for ($stopId, $routeType)")
        }
    }
