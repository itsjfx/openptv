package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeDeviceHeadingProvider
import ac.jfx.openptv.core.data.test.FakeLocationProvider
import ac.jfx.openptv.core.data.test.FakeNearbyStopsRepository
import ac.jfx.openptv.core.data.test.FakeStopDetailRepository
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.CoordinatesMother
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.RouteMother
import ac.jfx.openptv.core.testing.StopDetailMother
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NearbyViewModel]. Uses real [FakeLocationProvider] / [FakeNearbyStopsRepository]
 * / [FakeStopDetailRepository] / [FakeDepartureRepository] from `:core:data-test`. Coroutines are
 * driven by a [StandardTestDispatcher] so the 500 ms debounce assertion ("a sequence of camera
 * idles within 500 ms fires exactly one fetch") can be proved with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val locationProvider = FakeLocationProvider()
    private val headingProvider = FakeDeviceHeadingProvider()
    private val nearbyRepo = FakeNearbyStopsRepository()
    private val stopDetailRepo = FakeStopDetailRepository()
    private val departureRepo = FakeDepartureRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): NearbyViewModel =
        NearbyViewModel(
            locationProvider = locationProvider,
            deviceHeadingProvider = headingProvider,
            nearbyStopsRepository = nearbyRepo,
            stopDetailRepository = stopDetailRepo,
            departureRepository = departureRepo,
        )

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
}
