package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.DepartureDataSource
import ac.jfx.openptv.core.testing.DepartureMother
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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Repository-end coverage for [DepartureRepositoryImpl]. Two test surfaces:
 *
 *  1. `getDepartures` — one-shot fetch. Result wrapping + cancellation propagation, same
 *     contract as the search repository.
 *  2. `observeDepartures` — 30 s polling Flow. Virtual time pins the tick cadence; Turbine
 *     drives the collection. The MockWebServer-backed test pins the full wire roundtrip with
 *     a mid-poll error and recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepartureRepositoryImplTest {
    // ---------- getDepartures one-shot ----------

    @Test
    fun `getDepartures success wraps mapped list`() =
        runTest {
            val expected = listOf(DepartureMother.aDeparture().build())
            val repo = DepartureRepositoryImpl(FakeDataSource(returning = expected))

            val result = repo.getDepartures(StopId(1071), RouteType.Train)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isEqualTo(expected)
        }

    @Test
    fun `getDepartures non-cancellation throwable becomes Result Error`() =
        runTest {
            val boom = IOException("offline")
            val repo = DepartureRepositoryImpl(FakeDataSource(throwing = boom))

            val result = repo.getDepartures(StopId(1071), RouteType.Train)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
        }

    @Test(expected = CancellationException::class)
    fun `getDepartures cancellation propagates`() =
        runTest {
            val repo =
                DepartureRepositoryImpl(
                    FakeDataSource(throwing = CancellationException("scope died")),
                )
            repo.getDepartures(StopId(1071), RouteType.Train)
        }

    // ---------- observeDepartures polling Flow ----------

    @Test
    fun `observe emits Loading then Success on first tick`() =
        runTest {
            val expected = listOf(DepartureMother.aDeparture().build())
            val repo = DepartureRepositoryImpl(FakeDataSource(returning = expected))

            repo.observeDepartures(StopId(1071), RouteType.Train).test {
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
            // Three different snapshots so each tick can be distinguished.
            val ds = FakeDataSource(returningSeries = SnapshotSeries.threeSnapshots())
            val repo = DepartureRepositoryImpl(ds)

            repo.observeDepartures(StopId(1071), RouteType.Train).test {
                // First cycle.
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat((awaitItem() as Result.Success).data).hasSize(1)

                // Advance to just before the next tick — no new items.
                advanceTimeBy(29_999)
                expectNoEvents()

                // One more millisecond and the delay completes; the loop iterates.
                advanceTimeBy(2)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat((awaitItem() as Result.Success).data).hasSize(2)

                // And again for the third tick.
                advanceTimeBy(30_000)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat((awaitItem() as Result.Success).data).hasSize(3)

                cancelAndIgnoreRemainingEvents()
            }

            assertThat(ds.callCount.get()).isAtLeast(3)
        }

    @Test
    fun `observe stops polling when collector cancels`() =
        runTest {
            val ds = FakeDataSource(returning = listOf(DepartureMother.aDeparture().build()))
            val repo = DepartureRepositoryImpl(ds)

            val job: Job =
                repo.observeDepartures(StopId(1071), RouteType.Train)
                    .onEach { /* drain */ }
                    .launchIn(this)

            // Let the first cycle complete.
            runCurrent()
            val firstCount = ds.callCount.get()
            assertThat(firstCount).isEqualTo(1)

            // Cancel mid-delay.
            advanceTimeBy(5_000)
            job.cancel()
            runCurrent()

            // Confirm no further fetches happen even if we advance past the 30 s mark.
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(ds.callCount.get()).isEqualTo(firstCount)
        }

    @Test
    fun `observe surfaces error mid-poll and recovers on next tick`() =
        runTest {
            val good = listOf(DepartureMother.aDeparture().build())
            val ds =
                FakeDataSource(
                    returningSeries =
                        SnapshotSeries.from(
                            listOf(
                                FakeOutcome.Returning(good),
                                FakeOutcome.Throwing(IOException("transient")),
                                FakeOutcome.Returning(good),
                            ),
                        ),
                )
            val repo = DepartureRepositoryImpl(ds)

            repo.observeDepartures(StopId(1071), RouteType.Train).test {
                // First cycle — success.
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat(awaitItem()).isInstanceOf(Result.Success::class.java)

                // Second cycle — error surfaces; loop is NOT broken.
                advanceTimeBy(30_001)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val errored = awaitItem()
                assertThat(errored).isInstanceOf(Result.Error::class.java)
                assertThat((errored as Result.Error).throwable).isInstanceOf(IOException::class.java)

                // Third cycle — recovers.
                advanceTimeBy(30_001)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val recovered = awaitItem()
                assertThat(recovered).isInstanceOf(Result.Success::class.java)
                assertThat((recovered as Result.Success).data).isEqualTo(good)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---------- Inline fakes ----------

    private sealed class FakeOutcome {
        data class Returning(val list: List<Departure>) : FakeOutcome()

        data class Throwing(val throwable: Throwable) : FakeOutcome()
    }

    /**
     * Deterministic per-call series. Past the end of the series the last entry repeats — keeps
     * tests that advance time generously from running off the end.
     */
    private class SnapshotSeries(
        private val items: List<FakeOutcome>,
    ) {
        fun outcomeAt(index: Int): FakeOutcome = items[minOf(index, items.lastIndex)]

        companion object {
            fun from(outcomes: List<FakeOutcome>): SnapshotSeries = SnapshotSeries(outcomes)

            fun threeSnapshots(): SnapshotSeries =
                SnapshotSeries(
                    listOf(
                        FakeOutcome.Returning(listOf(DepartureMother.aDeparture().withRunRef("r1").build())),
                        FakeOutcome.Returning(
                            listOf(
                                DepartureMother.aDeparture().withRunRef("r1").build(),
                                DepartureMother.aDeparture().withRunRef("r2").build(),
                            ),
                        ),
                        FakeOutcome.Returning(
                            listOf(
                                DepartureMother.aDeparture().withRunRef("r1").build(),
                                DepartureMother.aDeparture().withRunRef("r2").build(),
                                DepartureMother.aDeparture().withRunRef("r3").build(),
                            ),
                        ),
                    ),
                )
        }
    }

    private class FakeDataSource(
        private val returning: List<Departure>? = null,
        private val throwing: Throwable? = null,
        private val returningSeries: SnapshotSeries? = null,
    ) : DepartureDataSource {
        val callCount: AtomicInteger = AtomicInteger(0)

        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): List<Departure> {
            val index = callCount.getAndIncrement()
            throwing?.let { throw it }
            if (returningSeries != null) {
                return when (val outcome = returningSeries.outcomeAt(index)) {
                    is FakeOutcome.Returning -> outcome.list
                    is FakeOutcome.Throwing -> throw outcome.throwable
                }
            }
            return returning ?: emptyList()
        }
    }
}
