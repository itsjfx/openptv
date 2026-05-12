package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopSearchRepository
import ac.jfx.openptv.core.model.Stop

/**
 * Hand-written fake for [StopSearchRepository]. Lives in the test source set for the barebones
 * cut; promoted to `:core:data-test` (with `@TestInstallIn`) alongside the multi-module split.
 *
 * Enqueue results with [enqueueResult] (or [enqueueSuccess] / [enqueueError]); each call to
 * [searchStops] dequeues the next one. An empty queue returns an empty Success — never throws,
 * never blocks.
 */
class FakeStopSearchRepository : StopSearchRepository {
    private val queue: ArrayDeque<Result<List<Stop>>> = ArrayDeque()
    val requestedTerms: MutableList<String> = mutableListOf()

    fun enqueueResult(result: Result<List<Stop>>) {
        queue.addLast(result)
    }

    fun enqueueSuccess(stops: List<Stop>) {
        queue.addLast(Result.Success(stops))
    }

    fun enqueueError(throwable: Throwable) {
        queue.addLast(Result.Error(throwable))
    }

    override suspend fun searchStops(term: String): Result<List<Stop>> {
        requestedTerms += term
        return queue.removeFirstOrNull() ?: Result.Success(emptyList())
    }
}
