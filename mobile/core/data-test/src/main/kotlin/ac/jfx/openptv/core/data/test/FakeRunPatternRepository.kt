package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.RunPatternRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [RunPatternRepository], shaped like [FakeDepartureRepository]'s streamed
 * surface: [observeRunPattern] returns the inner [MutableSharedFlow]; tests drive emissions with
 * [emit] / the convenience helpers. Real polling isn't simulated — timing tests exercise the
 * production impl in `:core:data`.
 *
 * `@Singleton` so a `setUp()` emission lands on the same instance the ViewModel collects;
 * `replay = 1` so a late subscriber sees the most recent value.
 */
@Singleton
class FakeRunPatternRepository
    @Inject
    constructor() : RunPatternRepository {
        private val observedFlow = MutableSharedFlow<Result<RunPattern>>(replay = 1)
        val observedKeys: MutableList<Pair<RunRef, RouteType>> = mutableListOf()

        suspend fun emit(result: Result<RunPattern>) {
            observedFlow.emit(result)
        }

        suspend fun emitLoading() {
            observedFlow.emit(Result.Loading)
        }

        suspend fun emitSuccess(pattern: RunPattern) {
            observedFlow.emit(Result.Success(pattern))
        }

        suspend fun emitError(throwable: Throwable) {
            observedFlow.emit(Result.Error(throwable))
        }

        override fun observeRunPattern(
            runRef: RunRef,
            routeType: RouteType,
        ): Flow<Result<RunPattern>> {
            observedKeys += runRef to routeType
            return observedFlow.asSharedFlow()
        }
    }
