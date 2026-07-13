package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.JourneyPlannerRepository
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.Stop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [JourneyPlannerRepository] (issue #204), same shape as
 * [FakeDepartureRepository]:
 *
 *  - [getJourneys] dequeues a one-shot [Result] enqueued via [enqueueResult] (or the
 *    convenience helpers); empty queue fails loud.
 *  - [observeJourneys] returns the inner [MutableSharedFlow]. Tests drive it with [emit] /
 *    the convenience helpers; polling cadence is the production impl's concern.
 *
 * Call keys and time anchors are recorded so feature tests can assert the picked stops and the
 * selected time reach the repository seam.
 */
@Singleton
class FakeJourneyPlannerRepository
    @Inject
    constructor() : JourneyPlannerRepository {
        private val oneShotQueue: ArrayDeque<Result<List<JourneyOption>>> = ArrayDeque()

        private val observedFlow = MutableSharedFlow<Result<List<JourneyOption>>>(replay = 1)
        val observedKeys: MutableList<Pair<Stop, Stop>> = mutableListOf()
        val oneShotKeys: MutableList<Pair<Stop, Stop>> = mutableListOf()

        var lastOneShotAt: Instant? = null
            private set
        var lastObservedAt: Instant? = null
            private set

        override suspend fun getJourneys(
            origin: Stop,
            destination: Stop,
            at: Instant?,
        ): Result<List<JourneyOption>> {
            oneShotKeys += origin to destination
            lastOneShotAt = at
            check(oneShotQueue.isNotEmpty()) {
                "FakeJourneyPlannerRepository: no one-shot result enqueued — call enqueueResult() in the test"
            }
            return oneShotQueue.removeFirst()
        }

        override fun observeJourneys(
            origin: Stop,
            destination: Stop,
            at: Instant?,
        ): Flow<Result<List<JourneyOption>>> {
            observedKeys += origin to destination
            lastObservedAt = at
            return observedFlow
        }

        fun enqueueResult(result: Result<List<JourneyOption>>) {
            oneShotQueue += result
        }

        fun enqueueSuccess(options: List<JourneyOption>) = enqueueResult(Result.Success(options))

        fun enqueueError(throwable: Throwable) = enqueueResult(Result.Error(throwable))

        suspend fun emit(result: Result<List<JourneyOption>>) = observedFlow.emit(result)

        suspend fun emitSuccess(options: List<JourneyOption>) = emit(Result.Success(options))

        suspend fun emitError(throwable: Throwable) = emit(Result.Error(throwable))
    }
