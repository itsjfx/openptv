package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteRouteAtStopMother
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [FavouritesViewModel].
 *
 * Uses real [FakeFavouritesRepository] / [FakeDepartureRepository] from `:core:data-test`. No
 * MockK. Sort persistence was removed in issue #78 — the screen no longer surfaces sort UI, so
 * the previously-needed DataStore harness has been dropped.
 *
 * Coroutines: [StandardTestDispatcher] so we control timing precisely via `advanceUntilIdle` /
 * `advanceTimeBy` and the polling tick can be exercised by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val favouritesRepository = FakeFavouritesRepository()
    private val departureRepository = FakeDepartureRepository()
    private val clock = FakeClock(Instant.parse("2026-05-14T09:00:00Z"))
    private val formatter = RelativeTimeFormatter(clock)

    private var activeViewModel: FavouritesViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        favouritesRepository.clock = clock
    }

    @After
    fun tearDown() {
        // Always stop the tick before resetting the dispatcher so a still-running polling
        // coroutine doesn't bleed an uncaught exception into the next test's `runTest`.
        activeViewModel?.stopObserving()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): FavouritesViewModel =
        FavouritesViewModel(
            observeFavourites = ObserveFavouritesUseCase(favouritesRepository),
            reorderFavourites = ReorderFavouritesUseCase(favouritesRepository),
            loadNextDeparture = LoadNextDepartureUseCase(departureRepository),
            favouritesRepository = favouritesRepository,
            timeFormatter = formatter,
        ).also { activeViewModel = it }

    @Test
    fun `empty repository emits FavouritesUiState Empty`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value).isEqualTo(FavouritesUiState.Empty)
        }

    @Test
    fun `three favourites emit a Loaded state in position order`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111)
                        .withStopName("Brunswick").withRouteNumber("19")
                        .withPosition(0)
                        .build(),
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(2).withRouteId(22).withDirectionId(222)
                        .withStopName("Aberfeldie").withRouteNumber("57")
                        .withPosition(1)
                        .build(),
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(3).withRouteId(33).withDirectionId(333)
                        .withStopName("Carlton").withRouteNumber("96")
                        .withPosition(2)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.map { it.stopName })
                .containsExactly("Brunswick", "Aberfeldie", "Carlton")
                .inOrder()
            // No sort UI — edit mode and refreshing flags default to false.
            assertThat(loaded.editMode).isFalse()
            assertThat(loaded.isRefreshing).isFalse()
        }

    @Test
    fun `onReorder writes the new triple ordering to the repository`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(2).withRouteId(22).withDirectionId(222).withPosition(1)
                        .build(),
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(3).withRouteId(33).withDirectionId(333).withPosition(2)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            // Move row 0 to the tail.
            viewModel.onReorder(
                orderedKeys =
                    listOf(
                        FavouriteKey(2, 22, 222),
                        FavouriteKey(3, 33, 333),
                        FavouriteKey(1, 11, 111),
                    ),
            )
            advanceUntilIdle()

            // The fake's reorder mutates `position` to match the supplied order.
            val current = favouritesRepository.current.sortedBy { it.position }
            assertThat(current.map { it.stopId.value }).containsExactly(2, 3, 1).inOrder()
        }

    @Test
    fun `onSwipeDelete removes from repository and stashes pendingUndo with original position`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(2).withRouteId(22).withDirectionId(222).withPosition(1)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.onSwipeDelete(FavouriteKey(stopId = 2, routeId = 22, directionId = 222))
            advanceUntilIdle()

            // The repository no longer has the deleted row.
            assertThat(favouritesRepository.current.map { it.stopId.value }).containsExactly(1)

            // The pending undo carries the deleted row and its original position.
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val undo = loaded.pendingUndo
            assertThat(undo).isNotNull()
            assertThat(undo!!.originalPosition).isEqualTo(1)
            assertThat(undo.row.key.stopId).isEqualTo(2)
        }

    @Test
    fun `onUndoDelete re-adds the favourite and clears pendingUndo`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.onSwipeDelete(FavouriteKey(stopId = 1, routeId = 11, directionId = 111))
            advanceUntilIdle()
            assertThat(favouritesRepository.current).isEmpty()

            viewModel.onUndoDelete()
            advanceUntilIdle()

            assertThat(favouritesRepository.current).hasSize(1)
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.pendingUndo).isNull()
        }

    // ---------- edit mode (issue #78) ----------

    @Test
    fun `toggleEditMode flips Loaded editMode flag`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as FavouritesUiState.Loaded).editMode).isFalse()

            viewModel.toggleEditMode()
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as FavouritesUiState.Loaded).editMode).isTrue()

            viewModel.toggleEditMode()
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as FavouritesUiState.Loaded).editMode).isFalse()
        }

    @Test
    fun `setEditMode true then false toggles directly`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.setEditMode(true)
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as FavouritesUiState.Loaded).editMode).isTrue()
            viewModel.setEditMode(false)
            advanceUntilIdle()
            assertThat((viewModel.uiState.value as FavouritesUiState.Loaded).editMode).isFalse()
        }

    // ---------- pull-to-refresh (issue #78) ----------

    @Test
    fun `refresh flips isRefreshing and runs an extra fan-out cycle`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val dep =
                DepartureMother.aDeparture()
                    .withRouteId(11).withDirectionId(111)
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(dep))

            val viewModel = newViewModel()
            advanceUntilIdle()
            // No tick running — refresh() drives the whole fan-out.
            viewModel.refresh()
            advanceUntilIdle()

            // After the fan-out lands, isRefreshing flips back off.
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.isRefreshing).isFalse()
            assertThat(departureRepository.oneShotKeys).hasSize(1)
        }

    // ---------- 60 s tick + Semaphore-bounded fan-out ----------

    @Test
    fun `startObserving fetches next-departure for each favourite and exposes Loaded labels with both clock times`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            // Real-time tracking lags the timetable by 90 s — both should appear on the row.
            val matching =
                DepartureMother.aDeparture()
                    .withRouteId(11).withDirectionId(111)
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 6.minutes + 30.seconds)
                    .build()
            departureRepository.enqueueSuccess(listOf(matching))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val next = loaded.rows.single().nextDeparture as NextDepartureState.Loaded
            // Issue #78 part 2: both scheduled and live are exposed. Issue #89 moved the
            // actual clock-face formatting into the Compose layer (so a 12/24-hour flip
            // reflects without a tick), so the contract here is the two raw [Instant]s diverge.
            assertThat(next.scheduledUtc).isNotNull()
            assertThat(next.estimatedUtc).isNotNull()
            assertThat(next.estimatedUtc).isNotEqualTo(next.scheduledUtc)
        }

    @Test
    fun `tick re-runs after 60 s and re-queries departures`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val dep =
                DepartureMother.aDeparture()
                    .withRouteId(11).withDirectionId(111)
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(dep))
            departureRepository.enqueueSuccess(listOf(dep))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            assertThat(departureRepository.oneShotKeys).hasSize(1)

            advanceTimeBy(61.seconds.inWholeMilliseconds)
            runCurrent()
            runCurrent()
            viewModel.stopObserving()
            assertThat(departureRepository.oneShotKeys).hasSize(2)
        }

    @Test
    fun `transient Error keeps the previous Loaded label (no flicker)`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val dep =
                DepartureMother.aDeparture()
                    .withRouteId(11).withDirectionId(111)
                    .withScheduledDepartureUtc(clock.now() + 3.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 3.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(dep))
            departureRepository.enqueueError(java.io.IOException("boom"))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()

            val firstTick = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(firstTick.rows.single().nextDeparture)
                .isInstanceOf(NextDepartureState.Loaded::class.java)

            advanceTimeBy(61.seconds.inWholeMilliseconds)
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val secondTick = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(secondTick.rows.single().nextDeparture)
                .isInstanceOf(NextDepartureState.Loaded::class.java)
        }

    @Test
    fun `tick with no matching departure surfaces NextDepartureState Empty`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .build(),
                ),
            )
            val otherRoute =
                DepartureMother.aDeparture()
                    .withRouteId(99).withDirectionId(99)
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(otherRoute))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.single().nextDeparture).isEqualTo(NextDepartureState.Empty)
        }

    @Test
    fun `RouteType is preserved on each row (used by the bottom-nav stop-detail tap)`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111)
                        .withRouteType(RouteType.Tram).withPosition(0)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.single().routeType).isEqualTo(RouteType.Tram)
        }

    @Test
    fun `onUndoDelete preserves lat lng so the restored favourite keeps its geo`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111).withPosition(0)
                        .withLat(-37.8183).withLng(144.9671)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.onSwipeDelete(FavouriteKey(stopId = 1, routeId = 11, directionId = 111))
            advanceUntilIdle()
            viewModel.onUndoDelete()
            advanceUntilIdle()

            val restored = favouritesRepository.current.single()
            assertThat(restored.lat).isEqualTo(-37.8183)
            assertThat(restored.lng).isEqualTo(144.9671)
        }

    /** A `Clock` that returns a fixed instant. */
    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
