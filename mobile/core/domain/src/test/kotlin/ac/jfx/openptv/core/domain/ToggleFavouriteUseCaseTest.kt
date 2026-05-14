package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.RouteMother
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for [ToggleFavouriteUseCase] against the hand-written [FakeFavouritesRepository] — per
 * `CLAUDE.md`, mocking an interface that already has a fake is a code smell.
 */
class ToggleFavouriteUseCaseTest {
    private val repository = FakeFavouritesRepository()
    private val useCase = ToggleFavouriteUseCase(repository)

    private val stop = StopMother.aStop().withRouteType(RouteType.Tram).build()
    private val route =
        RouteMother.aRoute()
            .withId(ROUTE_ID)
            .withNumber("19")
            .withName("North Coburg")
            .withRouteType(RouteType.Tram)
            .build()
    private val direction = Direction(id = DirectionId(DIRECTION_ID), name = "North Coburg")

    @Test
    fun `invoke on a not-favourited triple adds it and returns true`() =
        runTest {
            val result = useCase(stop = stop, route = route, direction = direction)

            assertThat(result).isTrue()
            val observed = repository.observe().first()
            assertThat(observed).hasSize(1)
            val only = observed.single()
            assertThat(only.stopId).isEqualTo(stop.id)
            assertThat(only.routeId).isEqualTo(route.id)
            assertThat(only.directionId).isEqualTo(direction.id)
            // Display-field copies from the inputs round-trip into the favourite.
            assertThat(only.stopName).isEqualTo(stop.name)
            assertThat(only.stopSuburb).isEqualTo(stop.suburb)
            assertThat(only.routeNumber).isEqualTo("19")
            assertThat(only.routeName).isEqualTo("North Coburg")
            assertThat(only.directionName).isEqualTo("North Coburg")
            assertThat(only.lat).isEqualTo(stop.latitude)
            assertThat(only.lng).isEqualTo(stop.longitude)
        }

    @Test
    fun `invoke on an already-favourited triple removes it and returns false`() =
        runTest {
            // First invoke: adds.
            useCase(stop = stop, route = route, direction = direction)
            assertThat(repository.observe().first()).hasSize(1)

            // Second invoke: removes.
            val result = useCase(stop = stop, route = route, direction = direction)

            assertThat(result).isFalse()
            assertThat(repository.observe().first()).isEmpty()
        }

    @Test
    fun `invoke on a different direction at the same stop and route adds without removing the other`() =
        runTest {
            // Star direction A.
            useCase(stop = stop, route = route, direction = direction)
            // Star direction B (different DirectionId).
            val otherDirection = Direction(id = DirectionId(OTHER_DIRECTION_ID), name = "Other")

            useCase(stop = stop, route = route, direction = otherDirection)

            val observed = repository.observe().first()
            assertThat(observed.map { it.directionId.value })
                .containsExactly(DIRECTION_ID, OTHER_DIRECTION_ID)
        }

    private companion object {
        const val ROUTE_ID = 1881
        const val DIRECTION_ID = 9
        const val OTHER_DIRECTION_ID = 10
    }
}
