package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeDeviceHeadingProvider
import ac.jfx.openptv.core.data.test.FakeLocationProvider
import ac.jfx.openptv.core.data.test.FakeNearbyStopsRepository
import ac.jfx.openptv.core.data.test.FakeStopDetailRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.MapRouteTypeFilterPreference
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.CoordinatesMother
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.RouteMother
import ac.jfx.openptv.core.testing.StopDetailMother
import ac.jfx.openptv.core.testing.StopMother
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [NearbyViewModel]. Uses real [FakeLocationProvider] / [FakeNearbyStopsRepository]
 * / [FakeStopDetailRepository] / [FakeDepartureRepository] from `:core:data-test`. Coroutines are
 * driven by a [StandardTestDispatcher] so the 500 ms debounce assertion ("a sequence of camera
 * idles within 500 ms fires exactly one fetch") can be proved with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class NearbyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val locationProvider = FakeLocationProvider()
    private val headingProvider = FakeDeviceHeadingProvider()
    private val nearbyRepo = FakeNearbyStopsRepository()
    private val stopDetailRepo = FakeStopDetailRepository()
    private val departureRepo = FakeDepartureRepository()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Real Preferences DataStore on a temp file backs the [UserPreferencesDataStore] the VM
     * reads/writes (issue #112). Matches the rest of the codebase: a hand-rolled fake would let
     * tests pass even if the wire format silently broke — the real DataStore catches that. The
     * file is fresh per test (new temp folder), so cross-test leakage is impossible.
     */
    private lateinit var prefsFile: File
    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var userPreferences: UserPreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefsFile = File(tempFolder.newFolder("datastore"), "openptv_user_prefs.preferences_pb")
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { prefsFile })
        userPreferences = UserPreferencesDataStore(dataStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun newViewModel(): NearbyViewModel =
        NearbyViewModel(
            locationProvider = locationProvider,
            deviceHeadingProvider = headingProvider,
            nearbyStopsRepository = nearbyRepo,
            stopDetailRepository = stopDetailRepo,
            departureRepository = departureRepo,
            userPreferences = userPreferences,
        )

    /**
     * Helper to pre-seed the persisted filter before constructing the VM. Tests that exercise
     * the "filter survives a process restart" path use this to put the user's previous selection
     * on disk first, then build a fresh VM and assert it picks up the seed via
     * `persistedFilter.await()`.
     */
    private suspend fun seedPersistedFilter(filter: Set<RouteType>) {
        MapRouteTypeFilterPreference.of(filter).put(storeScope, dataStore)
        // Drain — DataStore's actor is serialised, `data.first()` completes only after our write.
        dataStore.data.first()
    }

    @Test
    fun `initial state is PermissionUnasked`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value).isEqualTo(NearbyUiState.PermissionUnasked)
        }

    @Test
    fun `permission granted with last known fix enters Loaded centred on the fix`() =
        runTest(dispatcher) {
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(flinders)
            assertThat(loaded.userLocation).isEqualTo(flinders)
            assertThat(loaded.isFollowingUser).isTrue()
        }

    @Test
    fun `permission granted with no fix falls back to Melbourne CBD`() =
        runTest(dispatcher) {
            locationProvider.seed(null)
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(NearbyViewModel.MELBOURNE_CBD)
            assertThat(loaded.userLocation).isNull()
            assertThat(loaded.isFollowingUser).isFalse()
        }

    @Test
    fun `permission denied yields PermissionDenied state with CBD camera`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = false)
            advanceUntilIdle()

            val denied = viewModel.uiState.value as NearbyUiState.PermissionDenied
            assertThat(denied.camera.centre).isEqualTo(NearbyViewModel.MELBOURNE_CBD)
        }

    @Test
    fun `initial camera fetch fires after permission grant`() =
        runTest(dispatcher) {
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val stops = listOf(StopMother.aStop().build())
            nearbyRepo.enqueueSuccess(stops)
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            // Pump past the 500 ms debounce
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(nearbyRepo.requestedCalls).hasSize(1)
            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.pins).isEqualTo(stops)
        }

    @Test
    fun `camera idle within debounce window fires exactly one fetch`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            // Drain the initial fetch from grant before counting subsequent ones.
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Three idles within 500 ms — only the last should trigger a fetch.
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.81, 144.96), 13.0))
            advanceTimeBy(100)
            runCurrent()
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.82, 144.97), 13.0))
            advanceTimeBy(100)
            runCurrent()
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.83, 144.98), 13.0))
            // Now wait past the debounce
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            // The fetch should have been for the LAST idle's camera.
            val lastCall = nearbyRepo.requestedCalls.last()
            assertThat(lastCall.coordinates).isEqualTo(Coordinates(-37.83, 144.98))
        }

    @Test
    fun `camera idle 600 ms after the previous fetch fires a new fetch`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.81, 144.96), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val afterFirst = nearbyRepo.requestedCalls.size

            // ~600 ms later, another idle — should land as a second fetch.
            advanceTimeBy(100)
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.82, 144.97), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val afterSecond = nearbyRepo.requestedCalls.size

            assertThat(afterFirst - baselineCalls).isEqualTo(1)
            assertThat(afterSecond - afterFirst).isEqualTo(1)
        }

    // -------------------- camera-move-started cancels in-flight fetch (issue #109) --------------------
    //
    // With #108's LRU cache, the previously-rendered pins persist on screen during a drag — so we
    // can cancel an in-flight fetch the moment the user starts moving the camera without anything
    // visibly flickering. Saves bandwidth + PTV rate-limit on viewports the user is panning past.

    @Test
    fun `move-started inside the debounce window cancels the pending fetch`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // User releases drag → idle. We're mid-debounce when they start a new drag.
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.81, 144.96), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS / 2)
            // New drag begins — cancels the pending fetch.
            viewModel.onCameraMoveStarted()
            // Run past where the debounce WOULD have fired the fetch.
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // No new fetch — the cancel killed it before the network call landed.
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(0)
        }

    @Test
    fun `camera idle after move-started fires a fresh fetch once settled`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Idle → mid-debounce drag → idle. The first fetch must be cancelled; the second must
            // fire with the final camera position once the debounce elapses.
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.81, 144.96), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS / 2)
            viewModel.onCameraMoveStarted()
            advanceTimeBy(100)
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.83, 144.98), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            assertThat(nearbyRepo.requestedCalls.last().coordinates)
                .isEqualTo(Coordinates(-37.83, 144.98))
        }

    @Test
    fun `repeated move-started events without a settle never fire a fetch`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Simulate a long continuous drag — the user releases and grabs the map again without
            // ever letting the camera settle for the debounce window. No fetch should land.
            repeat(5) {
                viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.81, 144.96 + it * 0.01), 13.0))
                advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS / 4)
                viewModel.onCameraMoveStarted()
                advanceTimeBy(50)
            }
            advanceUntilIdle()

            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(0)
        }

    @Test
    fun `pin tap opens the sheet with the tapped stop`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val stop = StopMother.aStop().withId(1071).withName("Flinders Street").build()
            viewModel.onPinClicked(stop)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.stop).isEqualTo(stop)
        }

    @Test
    fun `sheet dismiss returns the state to Closed`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            val stop = StopMother.aStop().build()
            viewModel.onPinClicked(stop)
            advanceUntilIdle()

            viewModel.onSheetDismissed()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.pendingSheet).isEqualTo(SheetState.Closed)
        }

    @Test
    fun `empty result sets showEmptyHint`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            nearbyRepo.enqueueSuccess(emptyList())
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.showEmptyHint).isTrue()
            assertThat(loaded.pins).isEmpty()
        }

    @Test
    fun `repository error keeps pins empty without crashing`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            nearbyRepo.enqueueResult(Result.Error(RuntimeException("boom")))
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.pins).isEmpty()
            // Error path does NOT set the empty hint — the screen should fall back to its
            // "loading / unknown" surface, not lie that the area has no stops.
            assertThat(loaded.showEmptyHint).isFalse()
        }

    @Test
    fun `follow-me FAB recentres on the user fix at street zoom`() =
        runTest(dispatcher) {
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            // Move the camera elsewhere via a pan (camera-idle simulating a pan past the leash).
            viewModel.onCameraIdle(
                OpenPtvCameraState(Coordinates(lat = -37.8500, lng = 144.9631), 12.0),
            )
            advanceUntilIdle()
            val beforeFollow = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(beforeFollow.isFollowingUser).isFalse()

            viewModel.onFollowMeClicked()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(flinders)
            assertThat(loaded.camera.zoom).isEqualTo(NearbyViewModel.FOLLOW_ME_ZOOM)
            assertThat(loaded.isFollowingUser).isTrue()
        }

    // -------------------- route-type filter --------------------
    //
    // Invariant: `routeTypeFilter` is **never empty**. Defaulting to every visible mode (the
    // five chips: Train / Tram / Bus / V/Line / Night Bus) and disallowing the deselect-all
    // tap means the user can never end up with zero pins anywhere — see issue comments on PR
    // #84 ("filters not applying" / "must be on (≥1 selected)").

    @Test
    fun `default filter is the five visible modes`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEqualTo(DEFAULT_FILTER)
            // The initial fetch is fired with the default filter so the server responds with
            // exactly the modes the chip strip says are on.
            assertThat(nearbyRepo.requestedCalls.last().routeTypes).isEqualTo(DEFAULT_FILTER)
        }

    @Test
    fun `toggling an already-selected chip removes it and refires the fetch`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEqualTo(DEFAULT_FILTER - RouteType.Tram)
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            assertThat(nearbyRepo.requestedCalls.last().routeTypes)
                .isEqualTo(DEFAULT_FILTER - RouteType.Tram)
        }

    @Test
    fun `toggling a chip that's off adds it back and refires the fetch`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // First toggle: off
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.routeTypeFilter).doesNotContain(RouteType.Bus)
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Second toggle: on
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).contains(RouteType.Bus)
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
        }

    @Test
    fun `toggling Unknown is a no-op — Unknown is not a user-facing chip`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineFilter = viewModel.uiState.value.routeTypeFilter
            val baselineCalls = nearbyRepo.requestedCalls.size

            viewModel.onRouteTypeFilterToggled(RouteType.Unknown)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEqualTo(baselineFilter)
            assertThat(nearbyRepo.requestedCalls.size).isEqualTo(baselineCalls)
        }

    // -------------------- filter invariant: never empty --------------------
    //
    // Bug #2 on PR #84: "user can deselect every chip and end up with zero stops anywhere".
    // The toggle MUST refuse to deselect the only selected chip (instead of letting the user
    // walk into a dead-end state). Invariant: `routeTypeFilter.isNotEmpty()` after every
    // toggle, regardless of what sequence of taps got the user here.

    @Test
    fun `tapping the only selected chip is a no-op — invariant filter is never empty`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Whittle down to exactly one chip (Tram).
            (DEFAULT_FILTER - RouteType.Tram).forEach { mode ->
                viewModel.onRouteTypeFilterToggled(mode)
                advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
                advanceUntilIdle()
            }
            assertThat(viewModel.uiState.value.routeTypeFilter).containsExactly(RouteType.Tram)
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Try to deselect the last chip — should be ignored.
            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).containsExactly(RouteType.Tram)
            assertThat(viewModel.uiState.value.routeTypeFilter).isNotEmpty()
            // No fetch fires either — the no-op short-circuits before re-emitting.
            assertThat(nearbyRepo.requestedCalls.size).isEqualTo(baselineCalls)
        }

    @Test
    fun `every toggle leaves the filter non-empty across a long sequence`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Tap every chip three times each — even after every-on → every-off attempts the
            // filter should never go to empty.
            val sequence =
                listOf(
                    RouteType.Train,
                    RouteType.Tram,
                    RouteType.Bus,
                    RouteType.VLine,
                    RouteType.NightBus,
                    RouteType.Train,
                    RouteType.Tram,
                    RouteType.Bus,
                    RouteType.VLine,
                    RouteType.NightBus,
                    RouteType.Train,
                    RouteType.Tram,
                    RouteType.Bus,
                    RouteType.VLine,
                    RouteType.NightBus,
                )
            sequence.forEach { mode ->
                viewModel.onRouteTypeFilterToggled(mode)
                advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
                advanceUntilIdle()
                assertThat(viewModel.uiState.value.routeTypeFilter).isNotEmpty()
            }
        }

    // -------------------- filter race conditions --------------------
    //
    // Bug #1 on PR #84: "map filters apply inconsistently". The most plausible race: the user
    // toggles a chip while a debounced camera-idle fetch is mid-flight, or pans the map
    // immediately after toggling. The pipeline should always settle on the latest (camera,
    // filter) pair — `collectLatest` cancels stale fetches and the filter set queried on PTV
    // matches the last toggle.

    @Test
    fun `toggle mid-fetch — pending fetch is cancelled and the new filter wins`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Pan the camera, but only let half the debounce elapse.
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.81, 144.96), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS / 2)
            // While the debounce is still pending, toggle a chip off.
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            // Now drain the debounce.
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Exactly one fetch should have fired (the debounce coalesced the camera idle and
            // the filter toggle), and it MUST carry the post-toggle filter.
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            assertThat(nearbyRepo.requestedCalls.last().routeTypes).doesNotContain(RouteType.Bus)
            assertThat(viewModel.uiState.value.routeTypeFilter).doesNotContain(RouteType.Bus)
        }

    @Test
    fun `toggle then pan — last camera + last filter both win`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Toggle, then immediately pan. Both events sit in the debounce window.
            viewModel.onRouteTypeFilterToggled(RouteType.NightBus)
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.85, 144.99), 13.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // One fetch, with the latest camera centre and the post-toggle filter.
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            val lastCall = nearbyRepo.requestedCalls.last()
            assertThat(lastCall.coordinates).isEqualTo(Coordinates(-37.85, 144.99))
            assertThat(lastCall.routeTypes).doesNotContain(RouteType.NightBus)
        }

    @Test
    fun `subset selected — fetch carries only that subset to PTV`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Whittle down to Train + Tram only.
            (DEFAULT_FILTER - RouteType.Train - RouteType.Tram).forEach { mode ->
                viewModel.onRouteTypeFilterToggled(mode)
                advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
                advanceUntilIdle()
            }

            assertThat(viewModel.uiState.value.routeTypeFilter)
                .containsExactly(RouteType.Train, RouteType.Tram)
            assertThat(nearbyRepo.requestedCalls.last().routeTypes)
                .containsExactly(RouteType.Train, RouteType.Tram)
        }

    @Test
    fun `rapid same-chip taps coalesce — only one net change reaches the server`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            // Two quick taps on the same chip = net no-change, but each toggle re-emits.
            // The debounce should still coalesce them into ONE fetch.
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Net effect: filter ends up where it started; exactly one fetch fired.
            assertThat(viewModel.uiState.value.routeTypeFilter).isEqualTo(DEFAULT_FILTER)
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
        }

    // -------------------- bottom-sheet routes + departures --------------------

    @Test
    fun `pin tap fetches the stop detail and surfaces serving routes in the sheet`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val routes =
                listOf(
                    RouteMother.aRoute().withId(1).withName("Mernda").build(),
                    RouteMother.aRoute().withId(2).withName("Hurstbridge").build(),
                )
            stopDetailRepo.enqueueSuccess(
                StopDetailMother.aStopDetail().withServingRoutes(routes).build(),
            )
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val stop = StopMother.aStop().withId(1071).build()
            viewModel.onPinClicked(stop)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.routes).isEqualTo(routes)
        }

    @Test
    fun `pin tap subscribes to departures and surfaces a capped preview`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val stop = StopMother.aStop().withId(1071).build()
            viewModel.onPinClicked(stop)
            advanceUntilIdle()

            // Push four results onto the polled flow — sheet should clamp to the limit.
            val now = kotlinx.datetime.Instant.parse("2026-05-14T09:00:00Z")
            val departures =
                List(4) { i ->
                    DepartureMother.aDeparture()
                        .withRunRef("R-$i")
                        .withScheduledDepartureUtc(now.plus(kotlin.time.Duration.parse("PT${i + 1}M")))
                        .withEstimatedDepartureUtc(null)
                        .build()
                }
            departureRepo.emitSuccess(departures)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.departures).hasSize(StopBottomSheet.DEPARTURES_PREVIEW_LIMIT)
        }

    @Test
    fun `dismissing the sheet cancels the departures subscription`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val stop = StopMother.aStop().withId(1071).build()
            viewModel.onPinClicked(stop)
            advanceUntilIdle()
            assertThat(departureRepo.observedKeys).hasSize(1)

            viewModel.onSheetDismissed()
            advanceUntilIdle()
            // After the sheet is dismissed, a fresh departure emission must not push back into
            // the (now-Closed) sheet — the `updateSheet` guard short-circuits.
            departureRepo.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.pendingSheet).isEqualTo(SheetState.Closed)
        }

    // -------------------- list / row-tap (issue #80) --------------------
    //
    // The bottom-sheet "nearby stops list" reuses the existing `pins` projection from the same
    // `StateFlow<NearbyUiState>` the map reads, applies `filteredBy(routeTypeFilter)` at the
    // render seam, and dispatches row taps through `onPinClicked` — i.e. the same path a map
    // pin tap takes. The contract: the list's filtered/sorted projection MUST stay consistent
    // with the map pins, and a row tap MUST land on the same SheetState.Open shape a pin tap
    // produces.

    @Test
    fun `list and map share the same filtered pins projection`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val tramStop = StopMother.aStop().withId(1).withRouteType(RouteType.Tram).build()
            val busStop = StopMother.aStop().withId(2).withRouteType(RouteType.Bus).build()
            // Server returns both — the chip-toggle then filters down at the render seam (and
            // also re-fires the server fetch). For this assertion we only care that the
            // ViewModel's exposed `pins` is consistent with the filter.
            nearbyRepo.enqueueSuccess(listOf(tramStop, busStop))
            nearbyRepo.enqueueSuccess(listOf(tramStop))
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Toggle Bus off — leaves Train + Tram + V/Line + NightBus selected. Both surfaces
            // (map pins + list rows) read this same filter.
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val state = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(state.routeTypeFilter).doesNotContain(RouteType.Bus)
            assertThat(state.routeTypeFilter).contains(RouteType.Tram)
            // The list's projection and the map's projection are the SAME field on uiState,
            // filtered by the SAME helper. So the filter set is the proof.
            assertThat(state.pins).contains(tramStop)
        }

    @Test
    fun `row tap dispatches through onPinClicked landing the same SheetState as a pin tap`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val stop = StopMother.aStop().withId(2042).withName("Brunswick Street").build()
            // The list-row composable wires `onClick = { onPinClicked(stop) }` directly — same
            // entry point a map pin tap uses. The behaviour is identical at the VM seam, which
            // is what this assertion pins.
            viewModel.onPinClicked(stop)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.stop).isEqualTo(stop)
        }

    // -------------------- user location + heading (issue #99) --------------------
    //
    // The blue-dot indicator is sourced from a continuous `LocationProvider.observe()` flow plus
    // a separate `DeviceHeadingProvider.observe()` for the cone rotation. The contract here:
    //  - on permission grant, both subscriptions start; updates land in `userLocation` /
    //    `userBearing` on the `Loaded` state.
    //  - on permission deny, neither tracker is running and both fields stay null.
    //  - if the heading provider completes (no rotation sensor), `userBearing` stays at null —
    //    the screen renders the dot without a cone.
    //  - while follow-me is on, the camera tracks the user (centre moves with the fix).

    @Test
    fun `user location updates land in the Loaded state's userLocation field`() =
        runTest(dispatcher) {
            val seeded = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(seeded)
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val moved = Coordinates(lat = -37.81, lng = 144.97)
            locationProvider.emit(moved)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.userLocation).isEqualTo(moved)
        }

    @Test
    fun `following the user — camera tracks the moving fix`() =
        runTest(dispatcher) {
            val seeded = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(seeded)
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            // Follow-me starts on by default when a fix is present at grant time.
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).isFollowingUser).isTrue()

            val moved = Coordinates(lat = -37.82, lng = 144.98)
            locationProvider.emit(moved)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(moved)
        }

    @Test
    fun `not following the user — fix updates but camera holds`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            // A manual pan past the leash drops follow-me.
            viewModel.onCameraIdle(
                OpenPtvCameraState(Coordinates(lat = -37.86, lng = 144.99), 13.0),
            )
            advanceUntilIdle()
            val pannedCamera = (viewModel.uiState.value as NearbyUiState.Loaded).camera

            // A subsequent location update should NOT move the camera back.
            val moved = Coordinates(lat = -37.82, lng = 144.98)
            locationProvider.emit(moved)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.userLocation).isEqualTo(moved)
            assertThat(loaded.camera).isEqualTo(pannedCamera)
        }

    @Test
    fun `heading updates land in the Loaded state's userBearing field`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            // No emit yet — the sensor flow doesn't replay, so the field starts null.
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).userBearing).isNull()

            // 90° clockwise from north = east. The fake passes the value straight through; the
            // ViewModel doesn't transform it.
            headingProvider.emit(90f)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.userBearing).isEqualTo(90f)
        }

    @Test
    fun `heading provider completion leaves userBearing at null — no compass case`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            // Simulate "device has no rotation sensor" — the production provider closes the flow
            // immediately on subscribe in that case.
            headingProvider.complete()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.userBearing).isNull()
        }

    @Test
    fun `permission denied — neither user location nor heading is tracked`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = false)
            advanceUntilIdle()

            // Pushing values on either provider should not flip the state out of PermissionDenied.
            locationProvider.emit(CoordinatesMother.flindersStreet().build())
            // 0° = north — any value works here; we're testing that the state isn't flipped.
            headingProvider.emit(0f)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value).isInstanceOf(NearbyUiState.PermissionDenied::class.java)
        }

    // -------------------- LRU stop cache (issue #108) --------------------
    //
    // The map's pin list is a merged view over an LRU cache, not the raw fetch response. Panning
    // back into a region we've already loaded keeps those pins on-screen instead of dropping them
    // until the next fetch lands. Cache is cleared on a filter change so a "trams only" tap
    // doesn't keep bus pins visible. Bounded at `MAX_CACHED_STOPS` with eldest-out eviction.

    @Test
    fun `panning away and back keeps previously-fetched pins visible`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val flindersStop = StopMother.aStop().withId(1).withName("Flinders").build()
            val richmondStop = StopMother.aStop().withId(2).withName("Richmond").build()
            // First fetch (initial grant) returns Flinders.
            nearbyRepo.enqueueSuccess(listOf(flindersStop))
            // Second fetch (pan east) returns Richmond.
            nearbyRepo.enqueueSuccess(listOf(richmondStop))
            // Third fetch (pan back west) returns Flinders again — the user is back where they
            // started. The point of the test is that BEFORE this third fetch lands, the cache
            // already has Flinders visible, so the user doesn't see a flicker.
            nearbyRepo.enqueueSuccess(listOf(flindersStop))
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).pins).contains(flindersStop)

            // Pan east — fetch returns Richmond only. The merged pin list MUST still include
            // Flinders (it was cached on the first fetch).
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.82, 145.00), 15.0))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val afterEast = (viewModel.uiState.value as NearbyUiState.Loaded).pins
            assertThat(afterEast).containsExactly(flindersStop, richmondStop)

            // Pan back west — the cache already has Flinders + Richmond. Even if the fetch is in
            // flight, those pins must still be on screen. Verify the state BEFORE the debounce
            // elapses.
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.8183, 144.9671), 15.0))
            // Note: do NOT advance past the debounce yet — the assertion is that the existing
            // cache is what the user sees while the next fetch is still pending.
            val whilePanning = (viewModel.uiState.value as NearbyUiState.Loaded).pins
            assertThat(whilePanning).containsExactly(flindersStop, richmondStop)
        }

    @Test
    fun `filter change evicts stale-filter pins from the rendered list`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val tramStop = StopMother.aTramStop().withId(1).build()
            val busStop = StopMother.aBusStop().withId(2).build()
            // First fetch (default filter, all modes on) returns both.
            nearbyRepo.enqueueSuccess(listOf(tramStop, busStop))
            // Second fetch (after toggle, bus off) returns only the tram.
            nearbyRepo.enqueueSuccess(listOf(tramStop))
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            // Both stops cached and rendered.
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).pins)
                .containsExactly(tramStop, busStop)

            // Toggle Bus off. Cache MUST be cleared so the stale bus pin doesn't linger on the
            // map during the debounce window or after the next fetch lands.
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            // Inspect BEFORE the next fetch fires — the rendered list should already be empty
            // (cache cleared by the filter toggle).
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).pins).isEmpty()

            // After the next fetch with the bus-off filter, only the tram is on screen — no stale
            // bus pin even though it was cached pre-toggle.
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).pins).containsExactly(tramStop)
        }

    @Test
    fun `LRU eviction drops the oldest entry once the cache exceeds capacity`() =
        runTest(dispatcher) {
            // Direct test on the cache class — the VM bound (2000) is too big to exercise via
            // a fetch sequence, so we test the data structure that backs the merge directly.
            val cache = LruStopCache(maxSize = 2)
            val first = StopMother.aStop().withId(1).build()
            val second = StopMother.aStop().withId(2).build()
            val third = StopMother.aStop().withId(3).build()

            cache.put(first)
            cache.put(second)
            cache.put(third)

            // first was the eldest — evicted. Snapshot is in insertion (now LRU) order.
            assertThat(cache.snapshot().map { it.id }).containsExactly(second.id, third.id).inOrder()
            assertThat(cache.size()).isEqualTo(2)
        }

    @Test
    fun `re-inserting a cached stop bumps its LRU recency`() =
        runTest(dispatcher) {
            val cache = LruStopCache(maxSize = 2)
            val first = StopMother.aStop().withId(1).build()
            val second = StopMother.aStop().withId(2).build()
            val third = StopMother.aStop().withId(3).build()

            cache.put(first)
            cache.put(second)
            // Re-fetch the first one — its recency should bump above `second`, so a subsequent
            // third insertion evicts `second`, not `first`.
            cache.put(first)
            cache.put(third)

            assertThat(cache.snapshot().map { it.id }).containsExactly(first.id, third.id).inOrder()
        }

    @Test
    fun `tapping a different pin replaces the sheet contents and rebinds fetches`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val first = StopMother.aStop().withId(1).withName("First").build()
            val second = StopMother.aStop().withId(2).withName("Second").build()
            viewModel.onPinClicked(first)
            advanceUntilIdle()
            viewModel.onPinClicked(second)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.stop).isEqualTo(second)
            // Both stops should have triggered a routes fetch.
            assertThat(stopDetailRepo.requestedKeys.map { it.first.value }).containsExactly(1, 2).inOrder()
        }

    // -------------------- persisted route-type filter (issue #112) --------------------
    //
    // The chip selection survives an app restart via DataStore. On init the VM reads the
    // persisted set and seeds `routeTypeFilter` from it; on every chip toggle the new set is
    // written back. The seed read is gated by `persistedFilter.await()` inside
    // `onPermissionResult`, so even on a slow first emission the filter on screen matches the
    // user's previous session.

    @Test
    fun `persisted filter seeds routeTypeFilter on permission grant`() =
        runTest(dispatcher) {
            // Seed disk with "trams + trains only" — the previous session's selection.
            seedPersistedFilter(setOf(RouteType.Tram, RouteType.Train))
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // The seed wins over DEFAULT_FILTER — the chip strip opens on "Tram + Train" exactly.
            assertThat(viewModel.uiState.value.routeTypeFilter)
                .containsExactly(RouteType.Tram, RouteType.Train)
            // The initial fetch carries the persisted filter, not the default — so PTV's
            // `route_types` parameter reflects the user's choice from byte one.
            assertThat(nearbyRepo.requestedCalls.last().routeTypes)
                .containsExactly(RouteType.Tram, RouteType.Train)
        }

    @Test
    fun `persisted filter seeds routeTypeFilter on permission denial`() =
        runTest(dispatcher) {
            // The denied path still surfaces a chip strip over the CBD map — the seed must apply
            // there too. Same disk state, different branch.
            seedPersistedFilter(setOf(RouteType.Bus))
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = false)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).containsExactly(RouteType.Bus)
            assertThat(nearbyRepo.requestedCalls.last().routeTypes).containsExactly(RouteType.Bus)
        }

    @Test
    fun `empty datastore — VM seeds from DEFAULT_FILTER`() =
        runTest(dispatcher) {
            // Fresh install / first launch: nothing on disk. The `fromValue(null)` fallback
            // applies, so the VM seed is DEFAULT_FILTER — every chip on.
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()

            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEqualTo(DEFAULT_FILTER)
        }

    @Test
    fun `toggling a chip writes the new selection to datastore`() =
        runTest(dispatcher) {
            // After a toggle, a fresh VM constructed against the same DataStore should pick up
            // the new selection — proves the write went through.
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val first = newViewModel()
            first.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Toggle Bus off — write should land on disk.
            first.onRouteTypeFilterToggled(RouteType.Bus)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Read directly from the typed flow — round-trip the wire encoding.
            val persisted = userPreferences.mapRouteTypeFilter.first().value
            assertThat(persisted).isEqualTo(DEFAULT_FILTER - RouteType.Bus)
        }

    // -------------------- focus camera (issue #123) --------------------
    //
    // `focusOn(coordinates)` is the one-shot entry the stop-detail "show on map" action calls
    // via the `AppNavKey.Nearby(focusLat, focusLon)` route. It re-centres the camera at
    // [FOCUS_ZOOM], disengages follow-me, and schedules a fresh fetch for the new viewport.
    // The screen calls it once per entry via a `LaunchedEffect` keyed on the focus pair, so the
    // ViewModel itself doesn't need to guard against multiple calls — each call honours the
    // most recent coordinate.

    @Test
    fun `focusOn re-centres camera on the coordinate at street zoom and disengages follow-me`() =
        runTest(dispatcher) {
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            // Follow-me starts on when a fix is present at grant.
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).isFollowingUser).isTrue()

            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            viewModel.focusOn(richmond)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(richmond)
            assertThat(loaded.camera.zoom).isEqualTo(NearbyViewModel.FOCUS_ZOOM)
            // Follow-me is disengaged — the user has named a specific stop, not asked to track
            // themselves; the dot still renders but the camera no longer chases new fixes.
            assertThat(loaded.isFollowingUser).isFalse()
        }

    @Test
    fun `focusOn schedules a fetch for the focused viewport`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            viewModel.focusOn(richmond)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // One fresh fetch fired, for the focused coordinate — the user lands with pins
            // already on screen without needing to manually pan to trigger a refresh.
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            assertThat(nearbyRepo.requestedCalls.last().coordinates).isEqualTo(richmond)
        }

    @Test
    fun `focusOn from PermissionDenied still re-centres the camera`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = false)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value).isInstanceOf(NearbyUiState.PermissionDenied::class.java)

            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            viewModel.focusOn(richmond)
            advanceUntilIdle()

            val denied = viewModel.uiState.value as NearbyUiState.PermissionDenied
            assertThat(denied.camera.centre).isEqualTo(richmond)
            assertThat(denied.camera.zoom).isEqualTo(NearbyViewModel.FOCUS_ZOOM)
        }

    @Test
    fun `focusOn in PermissionUnasked leaves visible state unchanged — overlay still up`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            // Don't call onPermissionResult — stays in PermissionUnasked.

            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            viewModel.focusOn(richmond)
            advanceUntilIdle()

            // Visible state is unchanged — the permission overlay is still up and we don't
            // allocate a phantom Loaded/Denied variant. The focus coordinate is buffered
            // internally and consumed by onPermissionResult (see the bug-fix test below).
            assertThat(viewModel.uiState.value).isEqualTo(NearbyUiState.PermissionUnasked)
        }

    @Test
    fun `focusOn before permission grant — first Loaded state lands centred on the focus coords`() =
        runTest(dispatcher) {
            // Regression for PR #139: the stop-detail "show on map" affordance navigates to
            // Nearby with `(focusLat, focusLon)` baked into the destination args. The screen's
            // permission-pre-grant LaunchedEffect and the focus LaunchedEffect both fire on the
            // first composition. If focus runs first (state is still PermissionUnasked) the
            // request used to be silently dropped because the focus LaunchedEffect is keyed on
            // the focus pair, not on the state — so it doesn't re-fire when Loaded lands.
            //
            // Fix: the VM buffers the focus coord and consumes it the first time it transitions
            // into Loaded, so the user lands framed on the requested stop instead of their own
            // location (CBD here).
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val viewModel = newViewModel()

            // Focus arrives BEFORE permission resolves — this is the race we're pinning.
            val universityOfMelbourne = Coordinates(lat = -37.7964, lng = 144.9612)
            viewModel.focusOn(universityOfMelbourne)
            // No state change yet — overlay is up.
            assertThat(viewModel.uiState.value).isEqualTo(NearbyUiState.PermissionUnasked)

            // Permission resolves — the very first Loaded state MUST be centred on the focus
            // coords, not on the user's last-known fix.
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(universityOfMelbourne)
            assertThat(loaded.camera.zoom).isEqualTo(NearbyViewModel.FOCUS_ZOOM)
            // Follow-me is off — the user named a specific stop, not asked to chase their own
            // location. Matches the semantics of a direct focusOn call into an already-Loaded
            // state.
            assertThat(loaded.isFollowingUser).isFalse()
            // The fetch fires for the focused viewport — pins are ready without a manual pan.
            assertThat(nearbyRepo.requestedCalls.last().coordinates).isEqualTo(universityOfMelbourne)
        }

    @Test
    fun `focusOn before permission denial — first Denied state lands centred on the focus coords`() =
        runTest(dispatcher) {
            // Symmetric to the granted path: even if the user refuses location permission, the
            // map is still shown and we should honour their "frame on this stop" intent.
            val viewModel = newViewModel()

            val universityOfMelbourne = Coordinates(lat = -37.7964, lng = 144.9612)
            viewModel.focusOn(universityOfMelbourne)
            viewModel.onPermissionResult(granted = false)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val denied = viewModel.uiState.value as NearbyUiState.PermissionDenied
            assertThat(denied.camera.centre).isEqualTo(universityOfMelbourne)
            assertThat(denied.camera.zoom).isEqualTo(NearbyViewModel.FOCUS_ZOOM)
        }

    @Test
    fun `pending focus is consumed exactly once — subsequent permission flips do not refocus`() =
        runTest(dispatcher) {
            // Belt-and-braces: a permission revoke + re-grant must NOT refocus on the original
            // coord — the user has moved on, and the pendingFocus slot is one-shot.
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val viewModel = newViewModel()

            val universityOfMelbourne = Coordinates(lat = -37.7964, lng = 144.9612)
            viewModel.focusOn(universityOfMelbourne)
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            // First Loaded was centred on the focus — pending consumed.
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).camera.centre)
                .isEqualTo(universityOfMelbourne)

            // User pans elsewhere, then permission gets revoked + re-granted (e.g. flipped via
            // system settings while the app was backgrounded).
            viewModel.onCameraIdle(OpenPtvCameraState(Coordinates(-37.8500, 145.0000), 13.0))
            advanceUntilIdle()
            viewModel.onPermissionResult(granted = false)
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            // The re-granted Loaded state MUST NOT be centred back on the original focus —
            // it falls back to the user's last-known fix.
            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(flinders)
        }

    // -------------------- focus + auto-select preview (#139 review) --------------------
    //
    // The stop-detail "show on map" affordance now passes a `(stopId, routeType)` alongside the
    // focus coords so the Nearby screen lands with the bottom-sheet preview already open — same
    // shape a pin tap produces. The select consumption happens inside [scheduleFetch] once the
    // post-focus fetch lands carrying the matching stop, so the lookup uses the SAME `Stop`
    // projection a pin tap would (name, suburb, lat/lon all populated from the wire).

    @Test
    fun `focusOn with select args auto-opens the stop's bottom-sheet preview after the fetch lands`() =
        runTest(dispatcher) {
            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            val richmondStop =
                StopMother.aStop()
                    .withId(2042)
                    .withName("Richmond")
                    .withRouteType(RouteType.Train)
                    .withLatitude(richmond.lat)
                    .withLongitude(richmond.lng)
                    .build()
            // Fetch fired by focusOn returns the Richmond stop — the auto-select MUST find it.
            nearbyRepo.enqueueSuccess(listOf(richmondStop))
            // The sheet kicks off a stop-detail fetch as part of `onPinClicked`, so enqueue a
            // success or the routes one-shot will throw the fake's "no result enqueued" error.
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            viewModel.focusOn(
                coordinates = richmond,
                selectStopId = StopId(2042),
                selectRouteType = RouteType.Train,
            )
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            // Camera centred + sheet open on the requested stop — both halves of the request
            // landed in the same wave.
            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(richmond)
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.stop).isEqualTo(richmondStop)
        }

    @Test
    fun `focusOn before permission grant — select also lands after first fetch`() =
        runTest(dispatcher) {
            // Symmetric to the existing `focusOn before permission grant` test, but with select
            // args. The PermissionUnasked race buffers `pendingFocus` AND `pendingSelect`; the
            // first scheduled fetch (kicked off inside onPermissionResult's initialCamera path)
            // consumes the focus camera, and the fetch result consumes the select.
            val university = Coordinates(lat = -37.7964, lng = 144.9612)
            val universityStop =
                StopMother.aStop()
                    .withId(17715)
                    .withName("University of Melbourne")
                    .withRouteType(RouteType.Tram)
                    .withLatitude(university.lat)
                    .withLongitude(university.lng)
                    .build()
            nearbyRepo.enqueueSuccess(listOf(universityStop))
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()

            // Focus + select arrive BEFORE permission resolves — this is the race pinned by the
            // existing pendingFocus test, now extended with the select half.
            viewModel.focusOn(
                coordinates = university,
                selectStopId = StopId(17715),
                selectRouteType = RouteType.Tram,
            )
            assertThat(viewModel.uiState.value).isEqualTo(NearbyUiState.PermissionUnasked)

            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(university)
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.sheet.stop).isEqualTo(universityStop)
        }

    @Test
    fun `focusOn with select args — stop missing from fetch leaves sheet closed`() =
        runTest(dispatcher) {
            // Defensive: if the fetch result for the focused viewport doesn't contain the
            // requested stop (e.g. the user has toggled an incompatible filter mid-flight, or the
            // fetch returned an unrelated set), the select silently drops. Camera focus still
            // applies — the user can tap the pin manually if it lands in a subsequent fetch.
            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            val unrelated = StopMother.aStop().withId(9999).withName("Some Other Stop").build()
            nearbyRepo.enqueueSuccess(listOf(unrelated))
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            viewModel.focusOn(
                coordinates = richmond,
                selectStopId = StopId(2042),
                selectRouteType = RouteType.Train,
            )
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(richmond)
            assertThat(loaded.pendingSheet).isEqualTo(SheetState.Closed)
        }

    @Test
    fun `focusOn without select args — pendingSheet stays Closed`() =
        runTest(dispatcher) {
            // Belt-and-braces: the bottom-nav tab path and any other caller that passes only
            // coords must not produce a sheet. Proves that `pendingSelect` doesn't leak from a
            // previous focusOn call into a subsequent one — each invocation overwrites the slot.
            val flinders = CoordinatesMother.flindersStreet().build()
            locationProvider.seed(flinders)
            val richmondStop = StopMother.aStop().withId(2042).build()
            nearbyRepo.enqueueSuccess(listOf(richmondStop))
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            viewModel.focusOn(coordinates = richmond) // no select args
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.camera.centre).isEqualTo(richmond)
            assertThat(loaded.pendingSheet).isEqualTo(SheetState.Closed)
        }

    @Test
    fun `focusOn with select args — pendingSelect is one-shot, subsequent fetch does not re-open`() =
        runTest(dispatcher) {
            // Once the select has been consumed (sheet opened), a follow-up fetch that still
            // carries the same stop in its result must NOT re-open the sheet. Otherwise a user
            // who has dismissed the sheet would see it pop back open after the next camera idle.
            val richmond = Coordinates(lat = -37.8233, lng = 144.9913)
            val richmondStop =
                StopMother.aStop()
                    .withId(2042)
                    .withRouteType(RouteType.Train)
                    .withLatitude(richmond.lat)
                    .withLongitude(richmond.lng)
                    .build()
            // First fetch (focus) + second fetch (camera idle) both return Richmond. Two enqueues
            // because we want to assert the second fetch doesn't re-fire the select.
            nearbyRepo.enqueueSuccess(listOf(richmondStop))
            nearbyRepo.enqueueSuccess(listOf(richmondStop))
            stopDetailRepo.enqueueSuccess(StopDetailMother.aStopDetail().build())
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            viewModel.focusOn(
                coordinates = richmond,
                selectStopId = StopId(2042),
                selectRouteType = RouteType.Train,
            )
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            // Sheet opened — user dismisses it.
            assertThat((viewModel.uiState.value as NearbyUiState.Loaded).pendingSheet)
                .isInstanceOf(SheetState.Open::class.java)
            viewModel.onSheetDismissed()
            advanceUntilIdle()

            // Camera idle fires the second fetch — Richmond still in the result, but the
            // pendingSelect slot was cleared on the first hit, so no re-open.
            viewModel.onCameraIdle(OpenPtvCameraState(centre = richmond, zoom = NearbyViewModel.FOCUS_ZOOM))
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            assertThat(loaded.pendingSheet).isEqualTo(SheetState.Closed)
        }

    @Test
    fun `simulated app restart — fresh VM picks up the previous toggle`() =
        runTest(dispatcher) {
            // End-to-end shape of the user-visible behaviour: toggle, "restart" (build a fresh
            // VM against the same DataStore), assert the new VM seeds from the persisted set.
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val first = newViewModel()
            first.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            // Pare back to Tram-only — matches the manual emulator test in the PR plan.
            (DEFAULT_FILTER - RouteType.Tram).forEach { mode ->
                first.onRouteTypeFilterToggled(mode)
                advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
                advanceUntilIdle()
            }
            assertThat(first.uiState.value.routeTypeFilter).containsExactly(RouteType.Tram)

            // Build a fresh VM (same DataStore — same on-disk file as a process restart).
            val restarted = newViewModel()
            restarted.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(restarted.uiState.value.routeTypeFilter).containsExactly(RouteType.Tram)
        }
}
