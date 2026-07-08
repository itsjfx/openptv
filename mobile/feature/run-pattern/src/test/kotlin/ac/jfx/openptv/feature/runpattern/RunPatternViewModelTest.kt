package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeFollowedTripRepository
import ac.jfx.openptv.core.data.test.FakeRunPatternRepository
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.testing.FollowedTripMother
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
    private val followedTripRepository = FakeFollowedTripRepository()
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
            followedTripRepository = followedTripRepository,
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

    // ---------- Map data projection (issue #187) ----------

    @Test
    fun `loaded state builds map data from geopath and stop coordinates`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel(fromStopId = 1071)
            vm.startObserving()
            advanceUntilIdle()

            val pattern =
                RunPatternMother.aRunPattern()
                    .withGeopath(
                        listOf(listOf(Coordinates(-37.82, 145.05), Coordinates(-37.83, 145.06))),
                    )
                    .withStops(
                        listOf(
                            RunPatternStopMother.aPastPatternStop()
                                .withCoordinates(Coordinates(-37.81, 145.00))
                                .build(),
                            RunPatternStopMother.aPatternStop()
                                .withStopId(1071)
                                .withCoordinates(Coordinates(-37.818, 144.967))
                                .build(),
                        ),
                    )
                    .build()
            repository.emitSuccess(pattern)
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            val mapData = loaded.mapData!!
            assertThat(mapData.hasGeometry).isTrue()
            assertThat(mapData.polyline).hasSize(1)
            assertThat(mapData.markers).hasSize(2)
            // Exactly the origin (fromStopId 1071) marker carries the you-are-here flag.
            assertThat(mapData.markers.count { it.isOrigin }).isEqualTo(1)
            assertThat(mapData.markers.single { it.isOrigin }.coordinates)
                .isEqualTo(Coordinates(-37.818, 144.967))
            // The past stop's marker is dimmed.
            assertThat(mapData.markers.count { it.hasDeparted }).isEqualTo(1)
            assertThat(mapData.bounds).isNotNull()
        }

    @Test
    fun `map data is null when no geopath and no stop coordinates`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            // Default mother: empty geopath, stops without coordinates.
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            assertThat(loaded.mapData).isNull()
        }

    @Test
    fun `map data has geometry from geopath alone when stops lack coordinates`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            repository.emitSuccess(
                RunPatternMother.aRunPattern()
                    .withGeopath(listOf(listOf(Coordinates(-37.82, 145.05), Coordinates(-37.83, 145.06))))
                    .build(),
            )
            advanceUntilIdle()

            val loaded = vm.uiState.value.pattern as PatternState.Loaded
            assertThat(loaded.mapData?.hasGeometry).isTrue()
            assertThat(loaded.mapData?.markers).isEmpty()
        }

    // ---------- Follow this trip (issue #200) ----------

    @Test
    fun `followTrip stores a trip built from the loaded pattern`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel(fromStopId = 1162)
            vm.startObserving()
            advanceUntilIdle()
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            vm.followTrip()
            advanceUntilIdle()

            val stored = followedTripRepository.current!!
            assertThat(stored.runRef).isEqualTo(RunRef(RUN_REF))
            assertThat(stored.routeType).isEqualTo(RouteType.Train)
            assertThat(stored.fromStopId?.value).isEqualTo(1162)
            assertThat(stored.routeLabel).isEqualTo("Lilydale")
            assertThat(stored.destinationName).isEqualTo("Flinders Street")
            // The Mother's terminus (Flinders Street) has an estimate at 09:11 — the estimate
            // wins over the 09:10 schedule.
            assertThat(stored.completesAtUtc).isEqualTo(Instant.parse("2026-05-14T09:11:00Z"))
            assertThat(stored.followedAtUtc).isEqualTo(clock.now())
            assertThat(vm.uiState.value.isFollowingThisRun).isTrue()
        }

    @Test
    fun `followTrip before the pattern loads is a no-op`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.followTrip()
            advanceUntilIdle()

            assertThat(followedTripRepository.current).isNull()
        }

    @Test
    fun `followTrip with a different trip followed raises the replace confirmation`() =
        runTest(dispatcher.scheduler) {
            val other = FollowedTripMother.aFollowedTrip().withRunRef("111222").build()
            followedTripRepository.seed(other)

            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            vm.followTrip()
            advanceUntilIdle()

            // Nothing written yet — the dialog owns the decision.
            assertThat(followedTripRepository.current).isEqualTo(other)
            assertThat(vm.uiState.value.followReplaceCandidate).isEqualTo(other)
            assertThat(vm.uiState.value.isFollowingThisRun).isFalse()
        }

    @Test
    fun `confirmReplaceFollow replaces the other trip with this run`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().withRunRef("111222").build())

            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            vm.followTrip()
            advanceUntilIdle()
            vm.confirmReplaceFollow()
            advanceUntilIdle()

            assertThat(followedTripRepository.current!!.runRef).isEqualTo(RunRef(RUN_REF))
            assertThat(vm.uiState.value.followReplaceCandidate).isNull()
            assertThat(vm.uiState.value.isFollowingThisRun).isTrue()
        }

    @Test
    fun `dismissReplaceFollow keeps the other trip followed`() =
        runTest(dispatcher.scheduler) {
            val other = FollowedTripMother.aFollowedTrip().withRunRef("111222").build()
            followedTripRepository.seed(other)

            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            vm.followTrip()
            advanceUntilIdle()
            vm.dismissReplaceFollow()
            advanceUntilIdle()

            assertThat(followedTripRepository.current).isEqualTo(other)
            assertThat(vm.uiState.value.followReplaceCandidate).isNull()
            assertThat(vm.uiState.value.isFollowingThisRun).isFalse()
        }

    @Test
    fun `unfollowTrip clears the stored trip and the flag`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            advanceUntilIdle()
            assertThat(vm.uiState.value.isFollowingThisRun).isTrue()

            vm.unfollowTrip()
            advanceUntilIdle()

            assertThat(followedTripRepository.current).isNull()
            assertThat(vm.uiState.value.isFollowingThisRun).isFalse()
        }

    @Test
    fun `isFollowingThisRun reflects a pre-existing follow of this run`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.uiState.value.isFollowingThisRun).isTrue()
        }

    @Test
    fun `successful fetch refreshes the followed trip's completion time`() =
        runTest(dispatcher.scheduler) {
            // Followed earlier with the 09:11 estimate; the service is now running late.
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()

            val delayed =
                RunPatternMother.aRunPattern()
                    .withStops(
                        listOf(
                            RunPatternStopMother.aPastPatternStop().build(),
                            RunPatternStopMother.aPatternStop()
                                .withStopName("Flinders Street Railway Station")
                                .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                                .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:18:00Z"))
                                .build(),
                        ),
                    )
                    .build()
            repository.emitSuccess(delayed)
            advanceUntilIdle()

            val stored = followedTripRepository.current!!
            assertThat(stored.completesAtUtc).isEqualTo(Instant.parse("2026-05-14T09:18:00Z"))
            // followedAt is preserved — refresh is an update, not a re-follow.
            assertThat(stored.followedAtUtc)
                .isEqualTo(FollowedTripMother.aFollowedTrip().build().followedAtUtc)
        }

    @Test
    fun `successful fetch leaves a different followed run untouched`() =
        runTest(dispatcher.scheduler) {
            val other = FollowedTripMother.aFollowedTrip().withRunRef("111222").build()
            followedTripRepository.seed(other)

            val vm = viewModel()
            vm.startObserving()
            advanceUntilIdle()
            repository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            assertThat(followedTripRepository.current).isEqualTo(other)
        }

    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        private const val RUN_REF = "953527"
    }
}
