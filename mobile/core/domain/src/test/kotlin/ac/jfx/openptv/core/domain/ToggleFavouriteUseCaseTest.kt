package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.model.RouteType
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

    @Test
    fun `invoke on a not-favourited destination adds it and returns true`() =
        runTest {
            val result = useCase(stop = stop, destinationName = "North Coburg")

            assertThat(result).isTrue()
            val observed = repository.observe().first()
            assertThat(observed).hasSize(1)
            val only = observed.single()
            assertThat(only.stopId).isEqualTo(stop.id)
            assertThat(only.destinationKey).isEqualTo("north coburg")
            assertThat(only.destinationName).isEqualTo("North Coburg")
            assertThat(only.stopName).isEqualTo(stop.name)
            assertThat(only.stopSuburb).isEqualTo(stop.suburb)
            assertThat(only.routeType).isEqualTo(stop.routeType)
            assertThat(only.lat).isEqualTo(stop.latitude)
            assertThat(only.lng).isEqualTo(stop.longitude)
        }

    @Test
    fun `invoke on an already-favourited destination removes it and returns false`() =
        runTest {
            useCase(stop = stop, destinationName = "North Coburg")
            assertThat(repository.observe().first()).hasSize(1)

            val result = useCase(stop = stop, destinationName = "North Coburg")

            assertThat(result).isFalse()
            assertThat(repository.observe().first()).isEmpty()
        }

    @Test
    fun `invoke is case-insensitive — different casings of the same destination resolve as one`() =
        runTest {
            useCase(stop = stop, destinationName = "City")
            val result = useCase(stop = stop, destinationName = "city")

            // The second invoke saw the existing favourite (case-insensitive lookup) and removed it.
            assertThat(result).isFalse()
            assertThat(repository.observe().first()).isEmpty()
        }

    @Test
    fun `invoke on a different destination at the same stop adds without removing the other`() =
        runTest {
            useCase(stop = stop, destinationName = "City")
            useCase(stop = stop, destinationName = "Frankston")

            val observed = repository.observe().first()
            assertThat(observed.map { it.destinationKey })
                .containsExactly("city", "frankston")
        }
}
