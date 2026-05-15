package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.DepartureMother
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure pass-through test for [ObserveDeparturesUseCase]. The polling-loop semantics are
 * exercised in `:core:data`'s `DepartureRepositoryImplTest`; here we only assert that the use
 * case forwards parameters and returns the same Flow the repository returned.
 *
 * Uses [FakeDepartureRepository] rather than a hand-rolled stub — its `observeDepartures` is a
 * `replay = 1` SharedFlow, so emitting first and subscribing after still delivers the latest
 * value to the test. The fake never completes the flow, which is also closer to production
 * semantics than `flowOf(...)`.
 */
class ObserveDeparturesUseCaseTest {
    @Test
    fun `invoke returns the repository flow with arguments forwarded`() =
        runTest {
            val departures = listOf(DepartureMother.aDeparture().build())
            val repo = FakeDepartureRepository()
            val useCase = ObserveDeparturesUseCase(repo)

            useCase(StopId(42), RouteType.Tram).test {
                repo.emitLoading()
                assertThat(awaitItem()).isEqualTo(Result.Loading)

                repo.emitSuccess(departures)
                val success = awaitItem()
                assertThat(success).isInstanceOf(Result.Success::class.java)
                assertThat((success as Result.Success).data).isEqualTo(departures)

                cancelAndIgnoreRemainingEvents()
            }

            assertThat(repo.observedKeys).containsExactly(StopId(42) to RouteType.Tram)
        }
}
