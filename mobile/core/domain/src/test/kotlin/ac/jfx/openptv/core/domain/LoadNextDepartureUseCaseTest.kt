package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.RouteMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [LoadNextDepartureUseCase].
 *
 * Since the favourite unit became per-destination (issue #137), the use case picks the earliest
 * upcoming departure whose `direction.name.lowercase()` matches the favourited `destinationKey`.
 * Other routes that share the same destination (the multi-route "City" case) count as matches;
 * this is the entire point of the per-destination model.
 *
 * The repository is the hand-written [FakeDepartureRepository] — mocking a repo interface that
 * already has a fake is a code smell per `CLAUDE.md`.
 */
class LoadNextDepartureUseCaseTest {
    private val clock = FixedClock(Instant.parse("2026-05-14T09:00:00Z"))

    private val stopId = StopId(STOP_ID)
    private val routeType = RouteType.Train
    private val destinationKey = "city"

    @Test
    fun `picks the earliest upcoming departure matching the destination`() =
        runTest {
            val sooner =
                DepartureMother.aDeparture()
                    .withDirectionName("City")
                    .withRunRef("SOONER")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 6.minutes)
                    .build()
            val later =
                DepartureMother.aDeparture()
                    .withDirectionName("City")
                    .withRunRef("LATER")
                    .withScheduledDepartureUtc(clock.now() + 25.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 25.minutes)
                    .build()
            val repo = FakeDepartureRepository().apply { enqueueSuccess(listOf(later, sooner)) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            val picked = (result as Result.Success).data
            assertThat(picked).isNotNull()
            assertThat(picked!!.departure.runRef.value).isEqualTo("SOONER")
        }

    @Test
    fun `multi-route destination picks the soonest service regardless of route`() =
        runTest {
            // The favourite-destination model: at Caulfield the "City" block is fed by three lines.
            // The next service to City wins regardless of which line operates it.
            val cranbourneSoonest =
                DepartureMother.aDeparture()
                    .withRouteId(101).withDirectionName("City")
                    .withRunRef("CRANBOURNE-NEXT")
                    .withScheduledDepartureUtc(clock.now() + 4.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 4.minutes)
                    .build()
            val pakenhamLater =
                DepartureMother.aDeparture()
                    .withRouteId(102).withDirectionName("City")
                    .withRunRef("PAKENHAM-LATER")
                    .withScheduledDepartureUtc(clock.now() + 9.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 9.minutes)
                    .build()
            val repo = FakeDepartureRepository().apply { enqueueSuccess(listOf(pakenhamLater, cranbourneSoonest)) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            val picked = (result as Result.Success).data
            assertThat(picked!!.departure.runRef.value).isEqualTo("CRANBOURNE-NEXT")
        }

    @Test
    fun `joins the matching Route from the response sideload onto the picked departure`() =
        runTest {
            // Issue #137 regression: the favourites screen needs the line name ("Cranbourne") to
            // render the badge, not `#<routeId>`. The use case joins each picked Departure back
            // to the Route entry in the sideload by routeId.
            val cran =
                DepartureMother.aDeparture()
                    .withRouteId(101).withDirectionName("City").withRunRef("CRA")
                    .withScheduledDepartureUtc(clock.now() + 4.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 4.minutes)
                    .build()
            val cranRoute = RouteMother.aRoute().withId(101).withName("Cranbourne").withRouteType(RouteType.Train).build()
            val pakRoute = RouteMother.aRoute().withId(102).withName("Pakenham").withRouteType(RouteType.Train).build()
            val repo =
                FakeDepartureRepository().apply {
                    enqueueSuccessWithRoutes(departures = listOf(cran), routes = listOf(cranRoute, pakRoute))
                }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            val picked = (result as Result.Success).data
            assertThat(picked!!.route).isNotNull()
            assertThat(picked.route!!.name).isEqualTo("Cranbourne")
        }

    @Test
    fun `route is null when the response omits the sideload row for the picked routeId`() =
        runTest {
            // Defensive: PTV should always sideload routes the departures reference, but if a row
            // is missing the favourites screen falls back to `#<routeId>` via routeDisplayLabel.
            val dep =
                DepartureMother.aDeparture()
                    .withRouteId(101).withDirectionName("City").withRunRef("ORPHAN")
                    .withScheduledDepartureUtc(clock.now() + 4.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 4.minutes)
                    .build()
            val repo =
                FakeDepartureRepository().apply {
                    enqueueSuccessWithRoutes(departures = listOf(dep), routes = emptyList())
                }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            val picked = (result as Result.Success).data
            assertThat(picked).isNotNull()
            assertThat(picked!!.route).isNull()
        }

    @Test
    fun `ignores departures heading to other destinations`() =
        runTest {
            val wrongDestination =
                DepartureMother.aDeparture()
                    .withDirectionName("Frankston")
                    .withRunRef("WRONG-DEST")
                    .withScheduledDepartureUtc(clock.now() + 2.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 2.minutes)
                    .build()
            val rightDestination =
                DepartureMother.aDeparture()
                    .withDirectionName("City")
                    .withRunRef("RIGHT-DEST")
                    .withScheduledDepartureUtc(clock.now() + 6.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 6.minutes)
                    .build()
            val repo = FakeDepartureRepository().apply { enqueueSuccess(listOf(wrongDestination, rightDestination)) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            val picked = (result as Result.Success).data
            assertThat(picked!!.departure.runRef.value).isEqualTo("RIGHT-DEST")
        }

    @Test
    fun `destination match is case-insensitive`() =
        runTest {
            // PTV is consistent in practice; this is belt-and-braces. The use case lowercases the
            // direction name to compare with the lowercased destinationKey.
            val mixedCase =
                DepartureMother.aDeparture()
                    .withDirectionName("CITY")
                    .withRunRef("MIXED-CASE")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            val repo = FakeDepartureRepository().apply { enqueueSuccess(listOf(mixedCase)) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            val picked = (result as Result.Success).data
            assertThat(picked!!.departure.runRef.value).isEqualTo("MIXED-CASE")
        }

    @Test
    fun `returns Success(null) when no entries match the destination`() =
        runTest {
            val different =
                DepartureMother.aDeparture()
                    .withDirectionName("Frankston")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            val repo = FakeDepartureRepository().apply { enqueueSuccess(listOf(different)) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isNull()
        }

    @Test
    fun `returns Success(null) when the repository returns an empty list`() =
        runTest {
            val repo = FakeDepartureRepository().apply { enqueueSuccess(emptyList()) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isNull()
        }

    @Test
    fun `forwards Error from the repository`() =
        runTest {
            val boom = java.io.IOException("boom")
            val repo = FakeDepartureRepository().apply { enqueueError(boom) }
            val useCase = LoadNextDepartureUseCase(repo)

            val result = useCase(stopId, routeType, destinationKey)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
        }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val STOP_ID = 1071
    }
}
