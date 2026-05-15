package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
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

    @Test
    fun `default filter is empty meaning show all types`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEmpty()
            assertThat(nearbyRepo.requestedCalls.last().routeTypes).isEmpty()
        }

    @Test
    fun `toggling a route type adds it to the filter and refires the fetch`() =
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

            assertThat(viewModel.uiState.value.routeTypeFilter).containsExactly(RouteType.Tram)
            assertThat(nearbyRepo.requestedCalls.size - baselineCalls).isEqualTo(1)
            assertThat(nearbyRepo.requestedCalls.last().routeTypes).containsExactly(RouteType.Tram)
        }

    @Test
    fun `toggling the same route type twice removes it from the filter`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEmpty()
            assertThat(nearbyRepo.requestedCalls.last().routeTypes).isEmpty()
        }

    @Test
    fun `Unknown route type is ignored as a filter toggle`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()
            val baselineCalls = nearbyRepo.requestedCalls.size

            viewModel.onRouteTypeFilterToggled(RouteType.Unknown)
            advanceTimeBy(NearbyViewModel.CAMERA_IDLE_DEBOUNCE_MS + 1)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.routeTypeFilter).isEmpty()
            // Ignoring Unknown means no extra fetch — the filter didn't change.
            assertThat(nearbyRepo.requestedCalls.size).isEqualTo(baselineCalls)
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
