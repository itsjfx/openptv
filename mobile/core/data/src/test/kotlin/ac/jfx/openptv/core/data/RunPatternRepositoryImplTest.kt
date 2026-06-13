package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.network.RunPatternDataSource
import ac.jfx.openptv.core.testing.RunPatternMother
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Repository-end coverage for [RunPatternRepositoryImpl]: the 30 s polling Flow. Virtual time
 * pins the tick cadence; Turbine drives the collection. Same template as
 * [DepartureRepositoryImplTest]'s `observeDepartures` surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunPatternRepositoryImplTest {
    private val runRef = RunRef("953527")

    @Test
    fun `observe emits Loading then Success on first tick`() =
        runTest {
            val expected = RunPatternMother.aRunPattern().build()
            val repo = RunPatternRepositoryImpl(FakeDataSource(returning = expected))

            repo.observeRunPattern(runRef, RouteType.Train).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val first = awaitItem()
                assertThat(first).isInstanceOf(Result.Success::class.java)
                assertThat((first as Result.Success).data).isEqualTo(expected)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observe re-emits on 30 second tick`() =
        runTest {
            val ds = FakeDataSource(returning = RunPatternMother.aRunPattern().build())
            val repo = RunPatternRepositoryImpl(ds)

            repo.observeRunPattern(runRef, RouteType.Train).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat(awaitItem()).isInstanceOf(Result.Success::class.java)

                // Just before the next tick — nothing new.
                advanceTimeBy(29_999)
                expectNoEvents()

                // Crossing the 30 s boundary re-runs the loop.
                advanceTimeBy(2)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat(awaitItem()).isInstanceOf(Result.Success::class.java)

                cancelAndIgnoreRemainingEvents()
            }

            assertThat(ds.callCount.get()).isAtLeast(2)
        }

    @Test
    fun `observe stops polling when collector cancels`() =
        runTest {
            val ds = FakeDataSource(returning = RunPatternMother.aRunPattern().build())
            val repo = RunPatternRepositoryImpl(ds)

            val job: Job =
                repo.observeRunPattern(runRef, RouteType.Train)
                    .onEach { /* drain */ }
                    .launchIn(this)

            runCurrent()
            val firstCount = ds.callCount.get()
            assertThat(firstCount).isEqualTo(1)

            advanceTimeBy(5_000)
            job.cancel()
            runCurrent()

            advanceTimeBy(60_000)
            runCurrent()
            assertThat(ds.callCount.get()).isEqualTo(firstCount)
        }

    @Test
    fun `observe surfaces error mid-poll and recovers on next tick`() =
        runTest {
            val good = RunPatternMother.aRunPattern().build()
            val ds =
                FakeDataSource(
                    outcomes =
                        listOf(
                            FakeOutcome.Returning(good),
                            FakeOutcome.Throwing(IOException("transient")),
                            FakeOutcome.Returning(good),
                        ),
                )
            val repo = RunPatternRepositoryImpl(ds)

            repo.observeRunPattern(runRef, RouteType.Train).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat(awaitItem()).isInstanceOf(Result.Success::class.java)

                advanceTimeBy(30_001)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val errored = awaitItem()
                assertThat(errored).isInstanceOf(Result.Error::class.java)
                assertThat((errored as Result.Error).throwable).isInstanceOf(IOException::class.java)

                advanceTimeBy(30_001)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val recovered = awaitItem()
                assertThat(recovered).isInstanceOf(Result.Success::class.java)
                assertThat((recovered as Result.Success).data).isEqualTo(good)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observe passes runRef and routeType to the data source`() =
        runTest {
            val ds = FakeDataSource(returning = RunPatternMother.aRunPattern().build())
            val repo = RunPatternRepositoryImpl(ds)

            repo.observeRunPattern(runRef, RouteType.Tram).test {
                awaitItem()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(ds.lastRunRef).isEqualTo(runRef)
            assertThat(ds.lastRouteType).isEqualTo(RouteType.Tram)
        }

    // ---------- Inline fakes ----------

    private sealed class FakeOutcome {
        data class Returning(val pattern: RunPattern) : FakeOutcome()

        data class Throwing(val throwable: Throwable) : FakeOutcome()
    }

    private class FakeDataSource(
        private val returning: RunPattern? = null,
        private val outcomes: List<FakeOutcome>? = null,
    ) : RunPatternDataSource {
        val callCount: AtomicInteger = AtomicInteger(0)
        var lastRunRef: RunRef? = null
        var lastRouteType: RouteType? = null

        override suspend fun getRunPattern(
            runRef: RunRef,
            routeType: RouteType,
        ): RunPattern {
            val index = callCount.getAndIncrement()
            lastRunRef = runRef
            lastRouteType = routeType
            if (outcomes != null) {
                return when (val outcome = outcomes[minOf(index, outcomes.lastIndex)]) {
                    is FakeOutcome.Returning -> outcome.pattern
                    is FakeOutcome.Throwing -> throw outcome.throwable
                }
            }
            return returning ?: error("FakeDataSource: nothing to return")
        }
    }
}
