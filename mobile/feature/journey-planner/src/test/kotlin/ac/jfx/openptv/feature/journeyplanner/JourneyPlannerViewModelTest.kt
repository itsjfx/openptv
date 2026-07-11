package ac.jfx.openptv.feature.journeyplanner

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeJourneyPlannerRepository
import ac.jfx.openptv.core.data.test.FakeStopSearchRepository
import ac.jfx.openptv.core.testing.JourneyOptionMother
import ac.jfx.openptv.core.testing.StopMother
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * ViewModel coverage for the journey planner (issue #204): the inline picker pipeline, endpoint
 * selection/swap, the live-vs-pinned-time fetch split, and error/retry. Fakes from
 * `:core:data-test`, fixtures from `:core:testing`, virtual time throughout — the debounce is
 * crossed with `advanceTimeBy(350)` exactly like [SearchViewModelTest] in `:feature:search`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyPlannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val journeys = FakeJourneyPlannerRepository()
    private val search = FakeStopSearchRepository()
    private lateinit var viewModel: JourneyPlannerViewModel

    private val richmond = StopMother.aStop().withId(1162).withName("Richmond Station").build()
    private val burnley = StopMother.aStop().withId(1030).withName("Burnley Station").build()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel =
            JourneyPlannerViewModel(
                journeyPlannerRepository = journeys,
                stopSearchRepository = search,
                timeFormatter = RelativeTimeFormatter(FixedClock(Instant.parse("2026-05-14T09:00:00Z"))),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is fully idle`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.origin).isNull()
                assertThat(state.destination).isNull()
                assertThat(state.activeField).isNull()
                assertThat(state.picker).isEqualTo(StopPickerState.Idle)
                assertThat(state.results).isEqualTo(JourneyResultsState.Idle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `typing in the picker debounces then searches`() =
        runTest(dispatcher) {
            search.enqueueSuccess(listOf(richmond))
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onQueryChanged("rich")
                advanceTimeBy(350)
                advanceUntilIdle()
                val state = awaitUntil { it.picker is StopPickerState.Results }
                assertThat((state.picker as StopPickerState.Results).stops).containsExactly(richmond)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(search.requestedTerms).containsExactly("rich")
        }

    @Test
    fun `picking a stop fills the active field and closes the picker`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                advanceUntilIdle()
                viewModel.onStopPicked(richmond)
                advanceUntilIdle()
                val state = awaitUntil { it.origin != null }
                assertThat(state.origin).isEqualTo(richmond)
                assertThat(state.activeField).isNull()
                assertThat(state.query).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `both endpoints chosen starts the live journeys poll and maps Loaded`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                advanceUntilIdle()
                journeys.emitSuccess(listOf(option))
                advanceUntilIdle()
                val state = awaitUntil { it.results is JourneyResultsState.Loaded }
                assertThat((state.results as JourneyResultsState.Loaded).options).containsExactly(option)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).containsExactly(richmond to burnley)
            assertThat(journeys.lastObservedAt).isNull()
        }

    @Test
    fun `empty journey list maps to NoDirectServices`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                advanceUntilIdle()
                journeys.emitSuccess(emptyList())
                advanceUntilIdle()
                val state = awaitUntil { it.results != JourneyResultsState.Idle }
                assertThat(state.results).isEqualTo(JourneyResultsState.NoDirectServices)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pinned custom time uses the one-shot fetch with the anchor`() =
        runTest(dispatcher) {
            val pinned = Instant.parse("2026-05-15T18:00:00Z")
            journeys.enqueueSuccess(listOf(JourneyOptionMother.aJourneyOption().build()))
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                viewModel.onTimeSelected(pinned)
                advanceUntilIdle()
                val state = awaitUntil { it.results is JourneyResultsState.Loaded }
                assertThat(state.selectedTime).isEqualTo(pinned)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.oneShotKeys).containsExactly(richmond to burnley)
            assertThat(journeys.lastOneShotAt).isEqualTo(pinned)
        }

    @Test
    fun `clearing the pinned time returns to the live poll`() =
        runTest(dispatcher) {
            journeys.enqueueSuccess(emptyList())
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                viewModel.onTimeSelected(Instant.parse("2026-05-15T18:00:00Z"))
                advanceUntilIdle()
                viewModel.onTimeCleared()
                advanceUntilIdle()
                val state = awaitUntil { it.selectedTime == null }
                assertThat(state.selectedTime).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            // One-shot for the pinned window, then a fresh live subscription.
            assertThat(journeys.oneShotKeys).hasSize(1)
            assertThat(journeys.observedKeys).isNotEmpty()
        }

    @Test
    fun `swap exchanges origin and destination and re-subscribes`() =
        runTest(dispatcher) {
            // Plain collector rather than Turbine: StateFlow conflation across the two
            // advanceUntilIdle windows makes the exact emission count nondeterministic here;
            // asserting the settled end state is what the behaviour actually promises. Same
            // trade as the cancellation tests in DepartureRepositoryImplTest.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            advanceUntilIdle()
            viewModel.onSwapStops()
            advanceUntilIdle()

            val settled = states.last()
            assertThat(settled.origin).isEqualTo(burnley)
            assertThat(settled.destination).isEqualTo(richmond)
            assertThat(journeys.observedKeys)
                .containsExactly(richmond to burnley, burnley to richmond)
                .inOrder()
            job.cancel()
        }

    @Test
    fun `repository error maps to a user-facing reason and retry re-subscribes`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                advanceUntilIdle()
                journeys.emitError(IOException("socket bad"))
                advanceUntilIdle()
                val errored = awaitUntil { it.results is JourneyResultsState.Error }
                assertThat((errored.results as JourneyResultsState.Error).reason)
                    .contains("network")

                viewModel.onRetry()
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).hasSize(2)
        }

    @Test
    fun `results stay Idle until both endpoints are chosen`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                advanceUntilIdle()
                viewModel.onStopPicked(richmond)
                advanceUntilIdle()
                val state = awaitUntil { it.origin != null }
                assertThat(state.results).isEqualTo(JourneyResultsState.Idle)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).isEmpty()
            assertThat(journeys.oneShotKeys).isEmpty()
        }

    private fun pickBothStops() {
        viewModel.onFieldSelected(JourneyField.Origin)
        viewModel.onStopPicked(richmond)
        viewModel.onFieldSelected(JourneyField.Destination)
        viewModel.onStopPicked(burnley)
    }

    private suspend fun ReceiveTurbine<JourneyPlannerUiState>.awaitUntil(
        predicate: (JourneyPlannerUiState) -> Boolean,
    ): JourneyPlannerUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
