package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeRunPatternRepository
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Unit tests for [RunPatternViewModel]. [StandardTestDispatcher] (manual advance), Turbine for
 * state-flow assertions, the hand-written [FakeRunPatternRepository], and a `FakeClock` pinned
 * to `2026-05-14T09:00:00Z` so the Mother's default stops split deterministically into one past
 * (08:50) and two upcoming (09:05, 09:10) rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunPatternViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRunPatternRepository()
    private val clock = FakeClock(Instant.parse("2026-05-14T09:00:00Z"))
    private val formatter = RelativeTimeFormatter(clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(fromStopId: Int = RunPatternViewModel.NO_FROM_STOP): RunPatternViewModel =
        RunPatternViewModel(
            runRefValue = RUN_REF,
            routeTypeCode = RouteType.Train.toCode(),
            fromStopIdValue = fromStopId,
            observeRunPattern = ObserveRunPatternUseCase(repository),
            clock = clock,
            timeFormatter = formatter,
        )

    @Test
    fun `initial state is Loading`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            assertThat(vm.uiState.value.pattern).isEqualTo(PatternState.Loading)
            assertThat(vm.uiState.value.asOf).isNull()
        }

    @Test
    fun `success emission maps to Loaded with past-future split and asOf`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            val state = vm.uiState.value
            val loaded = state.pattern as PatternState.Loaded
            assertThat(loaded.routeLabel).isEqualTo("Lilydale")
            assertThat(loaded.directionName).isEqualTo("Flinders Street")
            assertThat(loaded.stops).hasSize(3)
            // 08:50 stop has departed under the 09:00 clock; 09:05 / 09:10 are upcoming.
            assertThat(loaded.stops.map { it.hasDeparted }).containsExactly(true, false, false).inOrder()
            assertThat(loaded.firstUpcomingIndex).isEqualTo(1)
            assertThat(state.asOf).isEqualTo(clock.now())
        }

    @Test
    fun `fromStopId marks the matching row as origin`() =
        runTest(dispatcher.scheduler) {
            // The Mother's third stop is Flinders Street, id 1071.
            val vm = viewModel(fromStopId = 1071)
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            assertThat(loaded.stops.map { it.isOrigin }).containsExactly(false, false, true).inOrder()
        }

    @Test
    fun `no fromStopId leaves every row unmarked`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            assertThat(loaded.stops.none { it.isOrigin }).isTrue()
        }

    @Test
    fun `missing route sideload yields null routeLabel`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(RunPatternMother.aRunPatternWithoutRoute().build())
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            assertThat(loaded.routeLabel).isNull()
        }

    @Test
    fun `empty stop list maps to Empty`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(RunPatternMother.aRunPattern().withStops(emptyList()).build())
            advanceUntilIdle()

            assertThat(vm.uiState.value.pattern).isEqualTo(PatternState.Empty)
        }

    @Test
    fun `all stops departed clamps firstUpcomingIndex to zero`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(
                RunPatternMother.aRunPattern()
                    .withStops(listOf(RunPatternStopMother.aPastPatternStop().build()))
                    .build(),
            )
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            assertThat(loaded.stops.single().hasDeparted).isTrue()
            assertThat(loaded.firstUpcomingIndex).isEqualTo(0)
        }

    @Test
    fun `error emission maps to Error with user-facing reason`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitError(IOException("offline"))
            advanceUntilIdle()

            val error = vm.uiState.value.pattern as PatternState.Error
            assertThat(error.reason).contains("network")
        }

    @Test
    fun `mid-poll Loading keeps the previous timeline on screen`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()
            repository.emitLoading()
            advanceUntilIdle()

            // The 30 s tick's Loading must not blank the list back to a skeleton.
            assertThat(vm.uiState.value.pattern).isInstanceOf(PatternState.Loaded::class.java)
        }

    @Test
    fun `error then next tick success recovers`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitError(IOException("transient"))
            advanceUntilIdle()
            assertThat(vm.uiState.value.pattern).isInstanceOf(PatternState.Error::class.java)

            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()
            assertThat(vm.uiState.value.pattern).isInstanceOf(PatternState.Loaded::class.java)
        }

    @Test
    fun `refresh flips isRefreshing until the next emission lands`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            vm.uiState.test {
                assertThat(awaitItem().isRefreshing).isFalse()

                vm.refresh()
                assertThat(awaitItem().isRefreshing).isTrue()

                advanceUntilIdle()
                // The fake's replay re-delivers the last success to the fresh collector,
                // clearing the flag — same observable behaviour as a real re-fetch.
                assertThat(awaitItem().isRefreshing).isFalse()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `startObserving subscribes with the assisted runRef and routeType`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            assertThat(repository.observedKeys).contains(RunRef(RUN_REF) to RouteType.Train)
        }

    @Test
    fun `stopObserving stops applying emissions`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()
            vm.stopObserving()

            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            assertThat(vm.uiState.value.pattern).isEqualTo(PatternState.Loading)
        }

    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        private const val RUN_REF = "953527"
    }
}
