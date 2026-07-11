package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteShape
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.RouteShapeDataSource
import ac.jfx.openptv.core.network.RunPatternDataSource
import ac.jfx.openptv.core.testing.RouteShapeMother
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
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
            val repo = RunPatternRepositoryImpl(FakeDataSource(returning = expected), FakeRouteShapeDataSource())

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
            val repo = RunPatternRepositoryImpl(ds, FakeRouteShapeDataSource())

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
            val repo = RunPatternRepositoryImpl(ds, FakeRouteShapeDataSource())

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
            val repo = RunPatternRepositoryImpl(ds, FakeRouteShapeDataSource())

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
            val repo = RunPatternRepositoryImpl(ds, FakeRouteShapeDataSource())

            repo.observeRunPattern(runRef, RouteType.Tram).test {
                awaitItem()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(ds.lastRunRef).isEqualTo(runRef)
            assertThat(ds.lastRouteType).isEqualTo(RouteType.Tram)
        }

    // ---------- Geopath enrichment (issue #187) ----------

    @Test
    fun `enriches pattern with geopath and stop coordinates for the matching direction`() =
        runTest {
            // Pattern with one stop whose id the shape provides coordinates for, in direction 1.
            val stop = RunPatternStopMother.aPatternStop().withStopId(1071).build()
            val pattern =
                RunPatternMother.aRunPattern()
                    .withStops(listOf(stop))
                    .withDirectionId(1)
                    .build()
            val shape =
                RouteShapeMother.aRouteShape()
                    .withGeopath(
                        mapOf(
                            1 to listOf(listOf(Coordinates(-37.82, 145.05), Coordinates(-37.83, 145.06))),
                        ),
                    )
                    .withStopCoordinates(mapOf(StopId(1071) to Coordinates(-37.818, 144.967)))
                    .build()
            val repo = RunPatternRepositoryImpl(FakeDataSource(returning = pattern), FakeRouteShapeDataSource(shape))

            repo.observeRunPattern(runRef, RouteType.Train).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val enriched = (awaitItem() as Result.Success).data
                assertThat(enriched.geopath).hasSize(1)
                assertThat(enriched.geopath.first()).hasSize(2)
                assertThat(enriched.stops.first().coordinates)
                    .isEqualTo(Coordinates(-37.818, 144.967))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `degrades gracefully when route shape fetch fails`() =
        runTest {
            val pattern = RunPatternMother.aRunPattern().build()
            val repo =
                RunPatternRepositoryImpl(
                    FakeDataSource(returning = pattern),
                    FakeRouteShapeDataSource(throwing = IOException("no shape")),
                )

            repo.observeRunPattern(runRef, RouteType.Train).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val result = awaitItem()
                // The pattern still succeeds; the geopath is just empty.
                assertThat(result).isInstanceOf(Result.Success::class.java)
                assertThat((result as Result.Success).data.geopath).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `skips route shape fetch when run has no route id`() =
        runTest {
            val pattern = RunPatternMother.aRunPatternWithoutRoute().build()
            val shapeDs = FakeRouteShapeDataSource()
            val repo = RunPatternRepositoryImpl(FakeDataSource(returning = pattern), shapeDs)

            repo.observeRunPattern(runRef, RouteType.Train).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat(awaitItem()).isInstanceOf(Result.Success::class.java)
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(shapeDs.callCount.get()).isEqualTo(0)
        }

    @Test
    fun `fetches route shape once and reuses it across polls`() =
        runTest {
            val stop = RunPatternStopMother.aPatternStop().withStopId(1071).build()
            val pattern =
                RunPatternMother.aRunPattern().withStops(listOf(stop)).withDirectionId(1).build()
            val shapeDs =
                FakeRouteShapeDataSource(
                    RouteShapeMother.aRouteShape()
                        .withStopCoordinates(mapOf(StopId(1071) to Coordinates(-37.8, 144.9)))
                        .build(),
                )
            val repo = RunPatternRepositoryImpl(FakeDataSource(returning = pattern), shapeDs)

            repo.observeRunPattern(runRef, RouteType.Train).test {
                awaitItem() // Loading
                awaitItem() // Success (first poll)
                advanceTimeBy(30_001)
                awaitItem() // Loading
                awaitItem() // Success (second poll)
                cancelAndIgnoreRemainingEvents()
            }

            // Geometry is static between polls — fetched exactly once.
            assertThat(shapeDs.callCount.get()).isEqualTo(1)
        }

    // ---------- Inline fakes ----------

    private class FakeRouteShapeDataSource(
        private val returning: RouteShape = RouteShape.EMPTY,
        private val throwing: Throwable? = null,
    ) : RouteShapeDataSource {
        val callCount: AtomicInteger = AtomicInteger(0)

        override suspend fun getRouteShape(
            routeId: RouteId,
            routeType: RouteType,
        ): RouteShape {
            callCount.incrementAndGet()
            throwing?.let { throw it }
            return returning
        }
    }

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
            dateUtc: Instant?,
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
