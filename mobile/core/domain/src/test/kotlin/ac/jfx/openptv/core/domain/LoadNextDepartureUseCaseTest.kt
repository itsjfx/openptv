package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.DepartureMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [LoadNextDepartureUseCase].
 *
 * The interesting case (issue #82): PTV's `/v3/departures` response is anchored at the start of
 * the current calendar day, not "now", so the response can include entries that have already
 * departed. Without an in-memory "is departed" filter the use case would return one of those
 * stale rows — which is exactly what the favourites screen used to show on the route subtext.
 * The fix mirrors stop-detail's `toGroupedList`: drop entries where
 * [RelativeTimeFormatter.isDeparted] is true before picking the earliest match.
 *
 * `Clock` is fixed to a stable instant. `RelativeTimeFormatter` is real (constructed against the
 * fake clock) — pure type, no need to mock per the testing priority order in `CLAUDE.md`.
 */
class LoadNextDepartureUseCaseTest {
    private val clock = FixedClock(Instant.parse("2026-05-14T09:00:00Z"))
    private val formatter = RelativeTimeFormatter(clock)

    private val stopId = StopId(STOP_ID)
    private val routeType = RouteType.Tram
    private val routeId = RouteId(ROUTE_ID)
    private val directionId = DirectionId(DIRECTION_ID)

    @Test
    fun `picks the first future departure on the favourited route, skipping departed entries`() =
        runTest {
            // PTV often returns the earlier-in-the-day departures because the request is anchored
            // at start-of-day, not "now". The earliest two on the favourited route are already in
            // the past; the use case must return the third.
            val pastA =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("PAST-A")
                    .withScheduledDepartureUtc(clock.now() - 30.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 28.minutes)
                    .build()
            val pastB =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("PAST-B")
                    .withScheduledDepartureUtc(clock.now() - 10.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 9.minutes)
                    .build()
            val futureNext =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("FUTURE-NEXT")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 6.minutes)
                    .build()
            val futureLater =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("FUTURE-LATER")
                    .withScheduledDepartureUtc(clock.now() + 25.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 25.minutes)
                    .build()
            val repo = StubRepository(Result.Success(listOf(pastA, pastB, futureNext, futureLater)))
            val useCase = LoadNextDepartureUseCase(repo, formatter)

            val result = useCase(stopId, routeType, routeId, directionId)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            val picked = (result as Result.Success).data
            assertThat(picked).isNotNull()
            assertThat(picked!!.runRef.value).isEqualTo("FUTURE-NEXT")
        }

    @Test
    fun `ignores departures on other routes even when they are sooner`() =
        runTest {
            // A different route on the same stop has an earlier upcoming departure — it must not
            // be picked. The favourited route's earliest upcoming wins.
            val otherRouteSooner =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("OTHER-SOONER")
                    .withScheduledDepartureUtc(clock.now() + 1.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 1.minutes)
                    .build()
            val ourRouteNext =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("OURS")
                    .withScheduledDepartureUtc(clock.now() + 7.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 7.minutes)
                    .build()
            val repo = StubRepository(Result.Success(listOf(otherRouteSooner, ourRouteNext)))
            val useCase = LoadNextDepartureUseCase(repo, formatter)

            val result = useCase(stopId, routeType, routeId, directionId)

            val picked = (result as Result.Success).data
            assertThat(picked!!.runRef.value).isEqualTo("OURS")
        }

    @Test
    fun `ignores departures in the wrong direction even on the favourited route`() =
        runTest {
            val wrongDirection =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(OTHER_DIRECTION_ID)
                    .withRunRef("WRONG-DIR")
                    .withScheduledDepartureUtc(clock.now() + 2.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 2.minutes)
                    .build()
            val rightDirection =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("RIGHT-DIR")
                    .withScheduledDepartureUtc(clock.now() + 6.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 6.minutes)
                    .build()
            val repo = StubRepository(Result.Success(listOf(wrongDirection, rightDirection)))
            val useCase = LoadNextDepartureUseCase(repo, formatter)

            val result = useCase(stopId, routeType, routeId, directionId)

            val picked = (result as Result.Success).data
            assertThat(picked!!.runRef.value).isEqualTo("RIGHT-DIR")
        }

    @Test
    fun `returns Success(null) when every match has departed`() =
        runTest {
            val onlyDeparted =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withScheduledDepartureUtc(clock.now() - 20.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 19.minutes)
                    .build()
            val repo = StubRepository(Result.Success(listOf(onlyDeparted)))
            val useCase = LoadNextDepartureUseCase(repo, formatter)

            val result = useCase(stopId, routeType, routeId, directionId)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isNull()
        }

    @Test
    fun `forwards Error from the repository`() =
        runTest {
            val boom = java.io.IOException("boom")
            val repo = StubRepository(Result.Error(boom))
            val useCase = LoadNextDepartureUseCase(repo, formatter)

            val result = useCase(stopId, routeType, routeId, directionId)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
        }

    /**
     * Minimal stub — only [getDepartures] is exercised by [LoadNextDepartureUseCase]. The polling
     * Flow / paging surface stay unused. A hand-written stub rather than [`FakeDepartureRepository`]
     * keeps `:core:domain` from depending on `:core:data-test` for one test class.
     */
    private class StubRepository(
        private val response: Result<List<Departure>>,
    ) : DepartureRepository {
        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Result<List<Departure>> = response

        override fun observeDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Flow<Result<List<Departure>>> = error("not used")

        override suspend fun loadMore(
            stopId: StopId,
            routeType: RouteType,
            after: Instant,
            maxResults: Int,
        ): Result<List<Departure>> = error("not used")
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val STOP_ID = 1071
        const val ROUTE_ID = 11
        const val OTHER_ROUTE_ID = 99
        const val DIRECTION_ID = 1
        const val OTHER_DIRECTION_ID = 2
    }
}
