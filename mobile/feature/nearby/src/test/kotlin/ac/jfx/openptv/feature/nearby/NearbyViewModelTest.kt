package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeLocationProvider
import ac.jfx.openptv.core.data.test.FakeNearbyStopsRepository
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.testing.CoordinatesMother
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
 * from `:core:data-test`. Coroutines are driven by a [StandardTestDispatcher] so the 500 ms
 * debounce assertion ("a sequence of camera idles within 500 ms fires exactly one fetch") can be
 * proved with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val locationProvider = FakeLocationProvider()
    private val nearbyRepo = FakeNearbyStopsRepository()

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
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()

            val stop = StopMother.aStop().withId(1071).withName("Flinders Street").build()
            viewModel.onPinClicked(stop)

            val loaded = viewModel.uiState.value as NearbyUiState.Loaded
            val sheet = loaded.pendingSheet as SheetState.Open
            assertThat(sheet.stop).isEqualTo(stop)
        }

    @Test
    fun `sheet dismiss returns the state to Closed`() =
        runTest(dispatcher) {
            locationProvider.seed(CoordinatesMother.flindersStreet().build())
            val viewModel = newViewModel()
            viewModel.onPermissionResult(granted = true)
            advanceUntilIdle()
            val stop = StopMother.aStop().build()
            viewModel.onPinClicked(stop)

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
}
