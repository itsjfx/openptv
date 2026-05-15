package ac.jfx.openptv.core.domain

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
 * Since issue #86 the repository asks PTV for `date_utc=now` + `look_backwards=false`, so the
 * response is already pre-filtered to upcoming-only. The use case's job collapses to:
 *   1. Keep only entries matching the favourited `(routeId, directionId)`.
 *   2. Return the earliest of those by `effectiveDepartureUtc` (`estimated` first, falling back
 *      to `scheduled`).
 *
 * `Clock` is fixed to a stable instant for clarity — the use case itself no longer reads the
 * clock, but a stable "now" keeps the test data legible.
 */
class LoadNextDepartureUseCaseTest {
    private val clock = FixedClock(Instant.parse("2026-05-14T09:00:00Z"))

    private val stopId = StopId(STOP_ID)
    private val routeType = RouteType.Tram
    private val routeId = RouteId(ROUTE_ID)
    private val directionId = DirectionId(DIRECTION_ID)

    @Test
    fun `picks the earliest upcoming departure on the favourited route`() =
        runTest {
            // PTV (via the repository) only returns upcoming entries since issue #86. The use
            // case picks the earliest by effective departure time — `estimated` wins over
            // `scheduled` when present.
            val sooner =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("SOONER")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 6.minutes)
                    .build()
            val later =
                DepartureMother.aDeparture()
                    .withRouteId(ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withRunRef("LATER")
                    .withScheduledDepartureUtc(clock.now() + 25.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 25.minutes)
                    .build()
            val repo = StubRepository(Result.Success(listOf(later, sooner)))
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, routeId, directionId)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            val picked = (result as Result.Success).data
            assertThat(picked).isNotNull()
            assertThat(picked!!.runRef.value).isEqualTo("SOONER")
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
            val useCase = LoadNextDepartureUseCase(repo)

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
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, routeId, directionId)

            val picked = (result as Result.Success).data
            assertThat(picked!!.runRef.value).isEqualTo("RIGHT-DIR")
        }

    @Test
    fun `returns Success(null) when no entries match the favourited route or direction`() =
        runTest {
            val differentRoute =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID).withDirectionId(DIRECTION_ID)
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            val repo = StubRepository(Result.Success(listOf(differentRoute)))
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, routeId, directionId)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isNull()
        }

    @Test
    fun `returns Success(null) when the repository returns an empty list`() =
        runTest {
            // Models the post-#86 contract: PTV/looker_backwards=false returned no upcoming rows
            // for this stop. The use case yields null and the favourites row renders "No upcoming
            // departures" rather than guessing from a stale entry.
            val repo = StubRepository(Result.Success(emptyList()))
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, routeId, directionId)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isNull()
        }

    @Test
    fun `forwards Error from the repository`() =
        runTest {
            val boom = java.io.IOException("boom")
            val repo = StubRepository(Result.Error(boom))
            val useCase = LoadNextDepartureUseCase(repo)

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
