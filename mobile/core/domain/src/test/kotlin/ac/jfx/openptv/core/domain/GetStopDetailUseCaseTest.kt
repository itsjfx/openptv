package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeStopDetailRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.StopDetailMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure pass-through test against the hand-written [FakeStopDetailRepository] — per `CLAUDE.md`,
 * mocking an interface that already has a fake is a code smell. Once the use case starts
 * orchestrating extra repositories (e.g. favourites in Phase 04) these assertions will grow.
 */
class GetStopDetailUseCaseTest {
    @Test
    fun `invoke forwards stopId and routeType to the repository and returns its result`() =
        runTest {
            val expected = Result.Success(StopDetailMother.aStopDetail().build())
            val repo = FakeStopDetailRepository().apply { enqueueResult(expected) }
            val useCase = GetStopDetailUseCase(repo)

            val result = useCase(StopId(42), RouteType.Tram)

            assertThat(result).isSameInstanceAs(expected)
            assertThat(repo.requestedKeys).containsExactly(StopId(42) to RouteType.Tram)
        }
}
