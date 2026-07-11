package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.DepartureDataSource
import ac.jfx.openptv.core.network.RouteStopsDataSource
import ac.jfx.openptv.core.network.RunPatternDataSource
import ac.jfx.openptv.core.network.StopDetailDataSource
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.RouteMother
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import ac.jfx.openptv.core.testing.StopDetailMother
import ac.jfx.openptv.core.testing.StopMother
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * Repository-end coverage for [JourneyPlannerRepositoryImpl] (issue #204). The fixture mirrors
 * the live corridor the algorithm was validated on: Richmond → Burnley on the Lilydale line,
 * direction 8 outbound (Richmond seq 6 < Burnley seq 8), direction 1 city-bound (Burnley 20 <
 * Richmond 22). Inline fakes per data source, same template as [RunPatternRepositoryImplTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyPlannerRepositoryImplTest {
    private val fixedNow: Instant = Instant.parse("2026-05-14T09:00:00Z")
    private val clock: Clock = FixedClock(fixedNow)

    private val richmond: Stop = StopMother.aStop().withId(RICHMOND).withName("Richmond Station").build()
    private val burnley: Stop = StopMother.aStop().withId(BURNLEY).withName("Burnley Station").build()

    private val lilydaleRoute =
        RouteMother.aRoute().withId(LILYDALE).withName("Lilydale").withRouteType(RouteType.Train).build()

    /** Outbound sequences on the Lilydale line: Richmond before Burnley. */
    private val outboundSequences =
        mapOf(StopId(RICHMOND) to 6, StopId(BURNLEY) to 8, StopId(LILYDALE_TERMINUS) to 27)

    /** City-bound: Burnley passed before Richmond — never a Richmond→Burnley journey. */
    private val cityBoundSequences =
        mapOf(StopId(BURNLEY) to 20, StopId(RICHMOND) to 22, StopId(FLINDERS) to 27)

    private fun outboundDeparture(
        runRef: String,
        scheduled: String,
        estimated: String? = null,
    ): Departure =
        DepartureMother.aDeparture()
            .withRouteId(LILYDALE)
            .withDirectionId(OUTBOUND)
            .withDirectionName("Lilydale")
            .withRunRef(runRef)
            .withScheduledDepartureUtc(Instant.parse(scheduled))
            .withEstimatedDepartureUtc(estimated?.let(Instant::parse))
            .build()

    private fun repository(
        departures: FakeDepartureDataSource,
        stopDetail: StopDetailDataSource = FakeStopDetailDataSource(burnleyServedBy(lilydaleRoute)),
        routeStops: FakeRouteStopsDataSource = FakeRouteStopsDataSource(defaultSequences()),
        patterns: FakeRunPatternDataSource = FakeRunPatternDataSource(),
    ): JourneyPlannerRepositoryImpl =
        JourneyPlannerRepositoryImpl(departures, stopDetail, routeStops, patterns, clock)

    private fun burnleyServedBy(vararg routes: ac.jfx.openptv.core.model.Route): FakeStopDetailDataSource.Config =
        FakeStopDetailDataSource.Config(
            details =
                mapOf(
                    StopId(BURNLEY) to
                        StopDetailMother.aStopDetail()
                            .withStop(burnley)
                            .withServingRoutes(routes.toList())
                            .build(),
                ),
        )

    private fun defaultSequences(): Map<Pair<RouteId, DirectionId>, Map<StopId, Int>> =
        mapOf(
            (RouteId(LILYDALE) to DirectionId(OUTBOUND)) to outboundSequences,
            (RouteId(LILYDALE) to DirectionId(CITY)) to cityBoundSequences,
        )

    // ---------- Join path ----------

    @Test
    fun `run listed at both stops joins by run_ref with arrival from the destination feed`() =
        runTest {
            val origin = outboundDeparture("951825", "2026-05-14T09:07:00Z", "2026-05-14T09:08:00Z")
            val atBurnley =
                outboundDeparture("951825", "2026-05-14T09:11:00Z", "2026-05-14T09:12:00Z")
            val patterns = FakeRunPatternDataSource()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(origin)),
                                StopId(BURNLEY) to atStop(listOf(atBurnley)),
                            ),
                        ),
                    patterns = patterns,
                )

            val result = repo.getJourneys(richmond, burnley)

            val options = (result as Result.Success).data
            assertThat(options).hasSize(1)
            with(options.single()) {
                assertThat(runRef).isEqualTo(RunRef("951825"))
                assertThat(route.name).isEqualTo("Lilydale")
                assertThat(scheduledDepartureUtc).isEqualTo(Instant.parse("2026-05-14T09:07:00Z"))
                assertThat(estimatedDepartureUtc).isEqualTo(Instant.parse("2026-05-14T09:08:00Z"))
                assertThat(scheduledArrivalUtc).isEqualTo(Instant.parse("2026-05-14T09:11:00Z"))
                assertThat(estimatedArrivalUtc).isEqualTo(Instant.parse("2026-05-14T09:12:00Z"))
            }
            // The join answered it — no pattern spend.
            assertThat(patterns.callCount.get()).isEqualTo(0)
        }

    @Test
    fun `destination entry earlier than the origin departure does not count as a join`() =
        runTest {
            // A city-bound run passes Burnley BEFORE Richmond, so the same run_ref appears at
            // both stops with the destination time earlier. That must not read as a journey —
            // and the city-bound sequence check must drop it without a pattern fetch.
            val cityBound =
                DepartureMother.aDeparture()
                    .withRouteId(LILYDALE)
                    .withDirectionId(CITY)
                    .withDirectionName("City")
                    .withRunRef("951226")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .build()
            val atBurnleyEarlier =
                DepartureMother.aDeparture()
                    .withRouteId(LILYDALE)
                    .withDirectionId(CITY)
                    .withRunRef("951226")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:02:00Z"))
                    .build()
            val patterns = FakeRunPatternDataSource()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(cityBound)),
                                StopId(BURNLEY) to atStop(listOf(atBurnleyEarlier)),
                            ),
                        ),
                    patterns = patterns,
                )

            val result = repo.getJourneys(richmond, burnley)

            assertThat((result as Result.Success).data).isEmpty()
            assertThat(patterns.callCount.get()).isEqualTo(0)
        }

    // ---------- Pattern fallback ----------

    @Test
    fun `run terminating at the destination is resolved via its pattern`() =
        runTest {
            // Terminating runs never appear in the terminus's departures feed (verified live at
            // Lilydale), so the join misses and the pattern must supply the arrival.
            val origin = outboundDeparture("951823", "2026-05-14T09:07:00Z")
            val pattern =
                RunPatternMother.aRunPattern()
                    .withStops(
                        listOf(
                            patternStop(RICHMOND, "2026-05-14T09:07:00Z"),
                            patternStop(BURNLEY, "2026-05-14T09:10:00Z", "2026-05-14T09:11:00Z"),
                        ),
                    )
                    .build()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(origin)),
                                StopId(BURNLEY) to atStop(emptyList()),
                            ),
                        ),
                    patterns = FakeRunPatternDataSource(mapOf(RunRef("951823") to pattern)),
                )

            val result = repo.getJourneys(richmond, burnley)

            val option = (result as Result.Success).data.single()
            assertThat(option.scheduledArrivalUtc).isEqualTo(Instant.parse("2026-05-14T09:10:00Z"))
            assertThat(option.estimatedArrivalUtc).isEqualTo(Instant.parse("2026-05-14T09:11:00Z"))
        }

    @Test
    fun `express run whose pattern skips the destination is dropped`() =
        runTest {
            val origin = outboundDeparture("951899", "2026-05-14T09:07:00Z")
            val expressPattern =
                RunPatternMother.aRunPattern()
                    .withStops(
                        listOf(
                            patternStop(RICHMOND, "2026-05-14T09:07:00Z"),
                            // Sails past Burnley straight to the terminus.
                            patternStop(LILYDALE_TERMINUS, "2026-05-14T09:45:00Z"),
                        ),
                    )
                    .build()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(origin)),
                                StopId(BURNLEY) to atStop(emptyList()),
                            ),
                        ),
                    patterns = FakeRunPatternDataSource(mapOf(RunRef("951899") to expressPattern)),
                )

            val result = repo.getJourneys(richmond, burnley)

            assertThat((result as Result.Success).data).isEmpty()
        }

    @Test
    fun `wrong-direction departure is filtered by sequences without a pattern fetch`() =
        runTest {
            val cityBound =
                DepartureMother.aDeparture()
                    .withRouteId(LILYDALE)
                    .withDirectionId(CITY)
                    .withRunRef("951300")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .build()
            val patterns = FakeRunPatternDataSource()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(cityBound)),
                                StopId(BURNLEY) to atStop(emptyList()),
                            ),
                        ),
                    patterns = patterns,
                )

            val result = repo.getJourneys(richmond, burnley)

            assertThat((result as Result.Success).data).isEmpty()
            assertThat(patterns.callCount.get()).isEqualTo(0)
        }

    @Test
    fun `sequence fetch failure falls through to the pattern check`() =
        runTest {
            val origin = outboundDeparture("951823", "2026-05-14T09:07:00Z")
            val pattern =
                RunPatternMother.aRunPattern()
                    .withStops(
                        listOf(
                            patternStop(RICHMOND, "2026-05-14T09:07:00Z"),
                            patternStop(BURNLEY, "2026-05-14T09:10:00Z"),
                        ),
                    )
                    .build()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(origin)),
                                StopId(BURNLEY) to atStop(emptyList()),
                            ),
                        ),
                    routeStops = FakeRouteStopsDataSource(throwing = IOException("sequences down")),
                    patterns = FakeRunPatternDataSource(mapOf(RunRef("951823") to pattern)),
                )

            val result = repo.getJourneys(richmond, burnley)

            assertThat((result as Result.Success).data).hasSize(1)
        }

    @Test
    fun `pattern fetch failure drops that candidate but keeps the rest`() =
        runTest {
            val joins = outboundDeparture("951825", "2026-05-14T09:07:00Z")
            val needsPattern = outboundDeparture("951827", "2026-05-14T09:27:00Z")
            val atBurnley = outboundDeparture("951825", "2026-05-14T09:11:00Z")
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(joins, needsPattern)),
                                StopId(BURNLEY) to atStop(listOf(atBurnley)),
                            ),
                        ),
                    patterns = FakeRunPatternDataSource(throwing = IOException("pattern down")),
                )

            val result = repo.getJourneys(richmond, burnley)

            val options = (result as Result.Success).data
            assertThat(options).hasSize(1)
            assertThat(options.single().runRef).isEqualTo(RunRef("951825"))
        }

    // ---------- Shared-route + input gating ----------

    @Test
    fun `departure on a route that does not serve the destination is excluded`() =
        runTest {
            val otherRoute =
                DepartureMother.aDeparture()
                    .withRouteId(MERNDA)
                    .withRunRef("880001")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:03:00Z"))
                    .build()
            val patterns = FakeRunPatternDataSource()
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(otherRoute)),
                                StopId(BURNLEY) to atStop(emptyList()),
                            ),
                        ),
                    patterns = patterns,
                )

            val result = repo.getJourneys(richmond, burnley)

            assertThat((result as Result.Success).data).isEmpty()
            assertThat(patterns.callCount.get()).isEqualTo(0)
        }

    @Test
    fun `cross-mode pair answers empty without any network call`() =
        runTest {
            val departures = FakeDepartureDataSource(emptyMap())
            val repo = repository(departures)
            val tramStop = StopMother.aTramStop().withId(2500).build()

            val result = repo.getJourneys(richmond, tramStop)

            assertThat((result as Result.Success).data).isEmpty()
            assertThat(departures.callCount.get()).isEqualTo(0)
        }

    @Test
    fun `same stop for origin and destination answers empty`() =
        runTest {
            val departures = FakeDepartureDataSource(emptyMap())
            val repo = repository(departures)

            val result = repo.getJourneys(richmond, richmond)

            assertThat((result as Result.Success).data).isEmpty()
            assertThat(departures.callCount.get()).isEqualTo(0)
        }

    @Test
    fun `options are sorted by effective departure`() =
        runTest {
            val later = outboundDeparture("951827", "2026-05-14T09:27:00Z")
            val earlier = outboundDeparture("951825", "2026-05-14T09:07:00Z")
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(later, earlier)),
                                StopId(BURNLEY) to
                                    atStop(
                                        listOf(
                                            outboundDeparture("951827", "2026-05-14T09:31:00Z"),
                                            outboundDeparture("951825", "2026-05-14T09:11:00Z"),
                                        ),
                                    ),
                            ),
                        ),
                )

            val result = repo.getJourneys(richmond, burnley)

            val options = (result as Result.Success).data
            assertThat(options.map { it.runRef.value }).containsExactly("951825", "951827").inOrder()
        }

    @Test
    fun `head departures fetch failure folds into Result Error`() =
        runTest {
            val repo = repository(FakeDepartureDataSource(emptyMap(), throwing = IOException("down")))

            val result = repo.getJourneys(richmond, burnley)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isInstanceOf(IOException::class.java)
        }

    @Test
    fun `null anchor applies the now grace window and custom anchor passes through verbatim`() =
        runTest {
            val departures =
                FakeDepartureDataSource(
                    mapOf(
                        StopId(RICHMOND) to atStop(emptyList()),
                        StopId(BURNLEY) to atStop(emptyList()),
                    ),
                )
            val repo = repository(departures)

            repo.getJourneys(richmond, burnley)
            assertThat(departures.lastDateUtc).isEqualTo(fixedNow - 2.minutes)

            val pinned = Instant.parse("2026-05-15T18:00:00Z")
            repo.getJourneys(richmond, burnley, at = pinned)
            assertThat(departures.lastDateUtc).isEqualTo(pinned)
        }

    // ---------- Polling + caching ----------

    @Test
    fun `observe emits Loading then Success and re-polls on the 30 second tick`() =
        runTest {
            val origin = outboundDeparture("951825", "2026-05-14T09:07:00Z")
            val atBurnley = outboundDeparture("951825", "2026-05-14T09:11:00Z")
            val departures =
                FakeDepartureDataSource(
                    mapOf(
                        StopId(RICHMOND) to atStop(listOf(origin)),
                        StopId(BURNLEY) to atStop(listOf(atBurnley)),
                    ),
                )
            val repo = repository(departures)

            repo.observeJourneys(richmond, burnley).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val first = awaitItem()
                assertThat(first).isInstanceOf(Result.Success::class.java)
                @Suppress("UNCHECKED_CAST")
                assertThat((first as Result.Success<List<JourneyOption>>).data).hasSize(1)

                advanceTimeBy(29_999)
                expectNoEvents()
                advanceTimeBy(2)
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                assertThat(awaitItem()).isInstanceOf(Result.Success::class.java)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `stop sequences and serving routes are cached across polls`() =
        runTest {
            // A candidate that never joins (terminating run) so the sequence gate runs each tick.
            val origin = outboundDeparture("951823", "2026-05-14T09:07:00Z")
            val pattern =
                RunPatternMother.aRunPattern()
                    .withStops(
                        listOf(
                            patternStop(RICHMOND, "2026-05-14T09:07:00Z"),
                            patternStop(BURNLEY, "2026-05-14T09:10:00Z"),
                        ),
                    )
                    .build()
            val routeStops = FakeRouteStopsDataSource(defaultSequences())
            val stopDetail = FakeStopDetailDataSource(burnleyServedBy(lilydaleRoute))
            val repo =
                repository(
                    departures =
                        FakeDepartureDataSource(
                            mapOf(
                                StopId(RICHMOND) to atStop(listOf(origin)),
                                StopId(BURNLEY) to atStop(emptyList()),
                            ),
                        ),
                    stopDetail = stopDetail,
                    routeStops = routeStops,
                    patterns = FakeRunPatternDataSource(mapOf(RunRef("951823") to pattern)),
                )

            repo.observeJourneys(richmond, burnley).test {
                repeat(2) { awaitItem() } // Loading + Success (first tick)
                advanceTimeBy(30_001)
                repeat(2) { awaitItem() } // Loading + Success (second tick)
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(routeStops.callCount.get()).isEqualTo(1)
            assertThat(stopDetail.callCount.get()).isEqualTo(1)
        }

    // ---------- Helpers + inline fakes ----------

    private fun atStop(departures: List<Departure>): DeparturesAtStop =
        DeparturesAtStop(departures = departures, routes = listOf(lilydaleRoute))

    private fun patternStop(
        stopId: Int,
        scheduled: String,
        estimated: String? = null,
    ) = RunPatternStopMother.aPatternStop()
        .withStopId(stopId)
        .withScheduledDepartureUtc(Instant.parse(scheduled))
        .withEstimatedDepartureUtc(estimated?.let(Instant::parse))
        .build()

    private class FakeDepartureDataSource(
        private val byStop: Map<StopId, DeparturesAtStop>,
        private val throwing: Throwable? = null,
    ) : DepartureDataSource {
        val callCount: AtomicInteger = AtomicInteger(0)
        var lastDateUtc: Instant? = null

        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
            dateUtc: Instant?,
            maxResults: Int?,
            lookBackwards: Boolean?,
        ): DeparturesAtStop {
            callCount.incrementAndGet()
            lastDateUtc = dateUtc
            throwing?.let { throw it }
            return byStop[stopId] ?: error("FakeDepartureDataSource: no fixture for $stopId")
        }
    }

    private class FakeStopDetailDataSource(
        private val config: Config,
    ) : StopDetailDataSource {
        data class Config(val details: Map<StopId, StopDetail>)

        val callCount: AtomicInteger = AtomicInteger(0)

        override suspend fun getStopDetail(
            stopId: StopId,
            routeType: RouteType,
        ): StopDetail? {
            callCount.incrementAndGet()
            return config.details[stopId]
        }
    }

    private class FakeRouteStopsDataSource(
        private val sequences: Map<Pair<RouteId, DirectionId>, Map<StopId, Int>> = emptyMap(),
        private val throwing: Throwable? = null,
    ) : RouteStopsDataSource {
        val callCount: AtomicInteger = AtomicInteger(0)

        override suspend fun getStopSequences(
            routeId: RouteId,
            routeType: RouteType,
            directionId: DirectionId,
        ): Map<StopId, Int> {
            callCount.incrementAndGet()
            throwing?.let { throw it }
            return sequences[routeId to directionId].orEmpty()
        }
    }

    private class FakeRunPatternDataSource(
        private val patterns: Map<RunRef, RunPattern> = emptyMap(),
        private val throwing: Throwable? = null,
    ) : RunPatternDataSource {
        val callCount: AtomicInteger = AtomicInteger(0)
        private val requested = ConcurrentHashMap.newKeySet<String>()

        override suspend fun getRunPattern(
            runRef: RunRef,
            routeType: RouteType,
        ): RunPattern {
            callCount.incrementAndGet()
            requested.add(runRef.value)
            throwing?.let { throw it }
            return patterns[runRef] ?: error("FakeRunPatternDataSource: no fixture for $runRef")
        }
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val RICHMOND = 1162
        const val BURNLEY = 1030
        const val FLINDERS = 1071
        const val LILYDALE_TERMINUS = 1115
        const val LILYDALE = 9
        const val MERNDA = 5
        const val OUTBOUND = 8
        const val CITY = 1
    }
}
