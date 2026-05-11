package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopSearchRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Hand-written fake. Lives in the test source set for the barebones cut; promoted to
 * `:core:data-test` (with `@TestInstallIn`) alongside the multi-module split.
 */
private class FakeStopSearchRepository : StopSearchRepository {
    var queue: ArrayDeque<Result<List<Stop>>> = ArrayDeque()
    val requestedTerms: MutableList<String> = mutableListOf()

    override suspend fun searchStops(term: String): Result<List<Stop>> {
        requestedTerms += term
        return queue.removeFirstOrNull() ?: Result.Success(emptyList())
    }
}

private fun aStop(id: Int = 1071, name: String = "Flinders Street Railway Station"): Stop =
    Stop(
        id = StopId(id),
        name = name,
        suburb = "Melbourne City",
        routeType = RouteType.Train,
        latitude = -37.8183,
        longitude = 144.9671,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeStopSearchRepository()
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SearchViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `query shorter than minimum stays Idle`() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
            viewModel.onQueryChanged("fl")
            advanceTimeBy(500)
            // No additional emission — short query is filtered before debounce hits the network.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repository.requestedTerms).isEmpty()
    }

    @Test
    fun `valid query transitions Loading then Results`() = runTest(dispatcher) {
        repository.queue.addLast(Result.Success(listOf(aStop())))
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
            viewModel.onQueryChanged("flinders")
            advanceTimeBy(350)
            assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
            advanceUntilIdle()
            val results = awaitItem()
            assertThat(results).isInstanceOf(SearchUiState.Results::class.java)
            assertThat((results as SearchUiState.Results).stops).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repository.requestedTerms).containsExactly("flinders")
    }

    @Test
    fun `valid query that returns empty list becomes Empty`() = runTest(dispatcher) {
        repository.queue.addLast(Result.Success(emptyList()))
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
            viewModel.onQueryChanged("zzz")
            advanceTimeBy(350)
            assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SearchUiState.Empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `IOException becomes user-facing Error state`() = runTest(dispatcher) {
        repository.queue.addLast(Result.Error(IOException("boom")))
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
            viewModel.onQueryChanged("flinders")
            advanceTimeBy(350)
            assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
            advanceUntilIdle()
            val terminal = awaitItem()
            assertThat(terminal).isInstanceOf(SearchUiState.Error::class.java)
            assertThat((terminal as SearchUiState.Error).reason).contains("network")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fast keystrokes coalesce into one upstream call`() = runTest(dispatcher) {
        repository.queue.addLast(Result.Success(listOf(aStop())))
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
            // Three rapid keystrokes within the debounce window.
            viewModel.onQueryChanged("fli")
            advanceTimeBy(50)
            viewModel.onQueryChanged("flin")
            advanceTimeBy(50)
            viewModel.onQueryChanged("flinders")
            advanceTimeBy(400)
            assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
            advanceUntilIdle()
            awaitItem() // Results
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repository.requestedTerms).containsExactly("flinders")
    }
}
