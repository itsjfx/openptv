package ac.jfx.openptv.ui

import ac.jfx.openptv.core.data.test.FakeFollowedTripRepository
import ac.jfx.openptv.core.data.test.FakeRunPatternRepository
import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.testing.FollowedTripMother
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for [AppViewModel] — the setup gate plus the followed-trip surface behind the
 * pinned "Return to your trip" bar (issue #200). [StandardTestDispatcher] with manual advance,
 * fakes from `:core:data-test`, and a *mutable* fake clock so completion can be crossed
 * mid-test the way a backgrounded app crosses it in the real world.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeSettingsRepository()
    private val followedTripRepository = FakeFollowedTripRepository()
    private val runPatternRepository = FakeRunPatternRepository()
    private val clock = MutableFakeClock(Instant.parse("2026-05-14T09:00:00Z"))

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var userPreferences: UserPreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Real facade over a temp-file DataStore — AppViewModel only holds it as a passthrough,
        // but a hand-rolled fake of a concrete class isn't possible and the real one is cheap.
        dataStoreScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        userPreferences =
            UserPreferencesDataStore(
                PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = {
                        File(tempFolder.newFolder("datastore"), "prefs.preferences_pb")
                    },
                ),
            )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(): AppViewModel =
        AppViewModel(
            settings = settings,
            userPreferences = userPreferences,
            followedTripRepository = followedTripRepository,
            observeRunPattern = ObserveRunPatternUseCase(runPatternRepository),
            clock = clock,
        )

    @Test
    fun `gate is Ready once setup is complete`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.gate.value).isEqualTo(GateState.Ready)
        }

    @Test
    fun `gate is NeedsSetup when setup has not completed`() =
        runTest(dispatcher.scheduler) {
            settings.seed(AppSettings(backendBaseUrl = "http://test.local/", setupCompleted = false))

            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.gate.value).isEqualTo(GateState.NeedsSetup)
        }

    @Test
    fun `followedTrip is null when nothing is followed`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.followedTrip.value).isNull()
        }

    @Test
    fun `followedTrip exposes an active trip for the bar`() =
        runTest(dispatcher.scheduler) {
            val trip = FollowedTripMother.aFollowedTrip().build()
            followedTripRepository.seed(trip)

            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.followedTrip.value).isEqualTo(trip)
            // Still stored — an active trip must not be evicted.
            assertThat(followedTripRepository.current).isEqualTo(trip)
        }

    @Test
    fun `a trip already complete at startup is hidden and evicted`() =
        runTest(dispatcher.scheduler) {
            // Completed 08:00 + 5 min grace, clock at 09:00 — the cold-relaunch-next-morning case.
            followedTripRepository.seed(FollowedTripMother.aCompletedFollowedTrip().build())

            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.followedTrip.value).isNull()
            assertThat(followedTripRepository.current).isNull()
        }

    @Test
    fun `evaluateFollowedTripCompletion unfollows once the clock passes completion plus grace`() =
        runTest(dispatcher.scheduler) {
            val trip = FollowedTripMother.aFollowedTrip().build()
            followedTripRepository.seed(trip)

            val vm = viewModel()
            advanceUntilIdle()
            assertThat(vm.followedTrip.value).isEqualTo(trip)

            // The app sits backgrounded past the trip's end; the user resumes it.
            clock.now = trip.completesAtUtc + FollowedTrip.COMPLETION_GRACE + 1.minutes
            vm.evaluateFollowedTripCompletion()
            advanceUntilIdle()

            assertThat(followedTripRepository.current).isNull()
            assertThat(vm.followedTrip.value).isNull()
        }

    @Test
    fun `evaluateFollowedTripCompletion keeps an active trip`() =
        runTest(dispatcher.scheduler) {
            val trip = FollowedTripMother.aFollowedTrip().build()
            followedTripRepository.seed(trip)

            val vm = viewModel()
            advanceUntilIdle()

            vm.evaluateFollowedTripCompletion()
            advanceUntilIdle()

            assertThat(followedTripRepository.current).isEqualTo(trip)
            assertThat(vm.followedTrip.value).isEqualTo(trip)
        }

    // --- Trip-progress polling behind the bar's "Next stop" line (PR #202 follow-up) ---

    @Test
    fun `no trip followed means the progress poll never touches the pattern repository`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()

            assertThat(runPatternRepository.observedKeys).isEmpty()
            assertThat(vm.tripProgress.value).isNull()
        }

    @Test
    fun `polling only runs between start and stop - the foreground window`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            advanceUntilIdle()
            // Backgrounded (no start yet): a followed trip alone must not poll.
            assertThat(runPatternRepository.observedKeys).isEmpty()

            vm.startTripProgressPolling()
            advanceUntilIdle()
            assertThat(runPatternRepository.observedKeys).hasSize(1)

            vm.stopTripProgressPolling()
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()
            // The emission after stop lands nowhere — collection is torn down.
            assertThat(vm.tripProgress.value).isNull()
        }

    @Test
    fun `a successful fetch derives the next upcoming stop for the bar`() =
        runTest(dispatcher.scheduler) {
            val trip = FollowedTripMother.aFollowedTrip().build()
            followedTripRepository.seed(trip)

            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            assertThat(runPatternRepository.observedKeys)
                .containsExactly(trip.runRef to trip.routeType)
            // Clock 09:00; Mother's pattern: Richmond 08:50 (past), East Richmond est 09:06.
            assertThat(vm.tripProgress.value?.nextStopName).isEqualTo("East Richmond Station")
        }

    @Test
    fun `progress advances as the clock passes each stop on a later poll tick`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()
            assertThat(vm.tripProgress.value?.nextStopName).isEqualTo("East Richmond Station")

            // Next 30 s tick re-delivers the same pattern, but the vehicle has moved on.
            clock.now = Instant.parse("2026-05-14T09:07:00Z")
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            assertThat(vm.tripProgress.value?.nextStopName)
                .isEqualTo("Flinders Street Railway Station")
        }

    @Test
    fun `a failed fetch keeps the last derived progress - the bar degrades, never errors`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()

            runPatternRepository.emitLoading()
            runPatternRepository.emitError(RuntimeException("proxy down"))
            advanceUntilIdle()

            assertThat(vm.tripProgress.value?.nextStopName).isEqualTo("East Richmond Station")
        }

    @Test
    fun `a run with no upcoming stops derives a null next stop`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()
            runPatternRepository.emitSuccess(
                RunPatternMother.aRunPattern()
                    .withStops(listOf(RunPatternStopMother.aPastPatternStop().build()))
                    .build(),
            )
            advanceUntilIdle()

            assertThat(vm.tripProgress.value).isNotNull()
            assertThat(vm.tripProgress.value?.nextStopName).isNull()
        }

    @Test
    fun `unfollowing clears the progress so a future follow never shows stale data`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
            advanceUntilIdle()
            assertThat(vm.tripProgress.value).isNotNull()

            vm.unfollowTrip()
            advanceUntilIdle()

            assertThat(vm.tripProgress.value).isNull()
        }

    @Test
    fun `following a different run re-keys the poll onto the new run`() =
        runTest(dispatcher.scheduler) {
            val first = FollowedTripMother.aFollowedTrip().build()
            followedTripRepository.seed(first)

            val vm = viewModel()
            vm.startTripProgressPolling()
            advanceUntilIdle()

            val second = FollowedTripMother.aFollowedTrip().withRunRef("111222").build()
            followedTripRepository.follow(second)
            advanceUntilIdle()

            assertThat(runPatternRepository.observedKeys)
                .containsExactly(
                    first.runRef to first.routeType,
                    second.runRef to second.routeType,
                )
                .inOrder()
        }

    @Test
    fun `unfollowTrip clears the stored trip and hides the bar`() =
        runTest(dispatcher.scheduler) {
            followedTripRepository.seed(FollowedTripMother.aFollowedTrip().build())

            val vm = viewModel()
            advanceUntilIdle()

            vm.followedTrip.test {
                assertThat(awaitItem()).isNotNull()

                vm.unfollowTrip()
                advanceUntilIdle()

                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(followedTripRepository.current).isNull()
        }

    private class MutableFakeClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }
}
