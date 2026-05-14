package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.DepartureMother
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure pass-through test for [ObserveDeparturesUseCase]. The polling-loop semantics are
 * exercised in `:core:data`'s `DepartureRepositoryImplTest`; here we only assert that the use
 * case forwards parameters and returns the same Flow the repository returned.
 */
class ObserveDeparturesUseCaseTest {
    @Test
    fun `invoke returns the repository flow with arguments forwarded`() =
        runTest {
            val departures = listOf(DepartureMother.aDeparture().build())
            val sourceFlow: Flow<Result<List<Departure>>> = flowOf(Result.Loading, Result.Success(departures))
            val repo = StubRepository(sourceFlow)
            val useCase = ObserveDeparturesUseCase(repo)

            useCase(StopId(42), RouteType.Tram).test {
                assertThat(awaitItem()).isEqualTo(Result.Loading)
                val success = awaitItem()
                assertThat(success).isInstanceOf(Result.Success::class.java)
                assertThat((success as Result.Success).data).isEqualTo(departures)
                awaitComplete()
            }

            assertThat(repo.calls).containsExactly(StopId(42) to RouteType.Tram)
        }

    private class StubRepository(
        private val source: Flow<Result<List<Departure>>>,
    ) : DepartureRepository {
        val calls: MutableList<Pair<StopId, RouteType>> = mutableListOf()

        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Result<List<Departure>> = error("not used")

        override fun observeDepartures(
            stopId: StopId,
            routeType: RouteType,
        ): Flow<Result<List<Departure>>> {
            calls += stopId to routeType
            return source
        }
    }
}
