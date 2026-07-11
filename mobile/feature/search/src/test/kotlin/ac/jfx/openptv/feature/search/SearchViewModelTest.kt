package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.data.test.FakeStopSearchRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.StopMother
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
    fun `initial state is Idle`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `single-character query triggers a search`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(listOf(StopMother.aStop().build()))
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                viewModel.onQueryChanged("f")
                advanceTimeBy(350)
                assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(SearchUiState.Results::class.java)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(repository.requestedTerms).containsExactly("f")
        }

    @Test
    fun `valid query transitions Loading then Results`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(listOf(StopMother.aStop().build()))
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
    fun `valid query that returns empty list becomes Empty`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(emptyList())
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
    fun `IOException becomes user-facing Error state`() =
        runTest(dispatcher) {
            repository.enqueueError(IOException("boom"))
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
    fun `search with no chips selected requests all modes`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(listOf(StopMother.aStop().build()))
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                viewModel.onQueryChanged("richmond")
                advanceTimeBy(350)
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(repository.requestedRouteTypes).containsExactly(emptySet<RouteType>())
        }

    @Test
    fun `toggling a chip re-runs the current query immediately with the filter on the wire`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(listOf(StopMother.aStop().build()))
            repository.enqueueSuccess(emptyList())
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                viewModel.onQueryChanged("richmond")
                advanceTimeBy(350)
                assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(SearchUiState.Results::class.java)

                viewModel.onRouteTypeFilterToggled(RouteType.Train)
                // Well inside the 300 ms debounce window — the filter combines in *after* the
                // debounce, so the re-query must not wait for it.
                advanceTimeBy(50)
                assertThat(awaitItem()).isEqualTo(SearchUiState.Loading)
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(SearchUiState.Empty)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(repository.requestedTerms).containsExactly("richmond", "richmond").inOrder()
            assertThat(repository.requestedRouteTypes)
                .containsExactly(emptySet<RouteType>(), setOf(RouteType.Train))
                .inOrder()
        }

    @Test
    fun `deselecting the last chip widens the query back to all modes`() =
        runTest(dispatcher) {
            repeat(3) { repository.enqueueSuccess(listOf(StopMother.aStop().build())) }
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                viewModel.onQueryChanged("richmond")
                advanceTimeBy(350)
                advanceUntilIdle()
                viewModel.onRouteTypeFilterToggled(RouteType.Tram)
                advanceUntilIdle()
                viewModel.onRouteTypeFilterToggled(RouteType.Tram)
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(repository.requestedRouteTypes)
                .containsExactly(emptySet<RouteType>(), setOf(RouteType.Tram), emptySet<RouteType>())
                .inOrder()
            assertThat(viewModel.routeTypeFilter.value).isEmpty()
        }

    @Test
    fun `toggling Unknown is a no-op`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(listOf(StopMother.aStop().build()))
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                viewModel.onQueryChanged("richmond")
                advanceTimeBy(350)
                advanceUntilIdle()
                viewModel.onRouteTypeFilterToggled(RouteType.Unknown)
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            // No second fetch — Unknown never reaches the filter set.
            assertThat(repository.requestedTerms).containsExactly("richmond")
            assertThat(viewModel.routeTypeFilter.value).isEmpty()
        }

    @Test
    fun `toggling a chip with a sub-minimum query stays Idle and off the network`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SearchUiState.Idle)
                viewModel.onRouteTypeFilterToggled(RouteType.Bus)
                advanceUntilIdle()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(repository.requestedTerms).isEmpty()
        }

    @Test
    fun `fast keystrokes coalesce into one upstream call`() =
        runTest(dispatcher) {
            repository.enqueueSuccess(listOf(StopMother.aStop().build()))
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
