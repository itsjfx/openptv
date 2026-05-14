package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteRouteAtStopMother
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [FavouritesViewModel].
 *
 * Uses real [FakeFavouritesRepository] / [FakeDepartureRepository] from `:core:data-test`. The
 * DataStore is the real Preferences-backed store, written to a temp directory — matches what
 * `:feature:settings` does in its androidTest and gives the tests real wire-format coverage.
 *
 * Coroutines: [StandardTestDispatcher] so we control timing precisely via `advanceUntilIdle` /
 * `advanceTimeBy` and the polling tick can be exercised by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesViewModelTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val favouritesRepository = FakeFavouritesRepository()
    private val departureRepository = FakeDepartureRepository()
    private val clock = FakeClock(Instant.parse("2026-05-14T09:00:00Z"))
    private val formatter = RelativeTimeFormatter(clock)

    private lateinit var preferencesDataStore: DataStore<Preferences>
    private lateinit var userPreferences: UserPreferencesDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private var activeViewModel: FavouritesViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // DataStore needs a real dispatcher for its internal file I/O (the test dispatcher
        // deadlocks on synchronous `edit { ... }` calls). Keep it on `Dispatchers.IO`; cross-
        // dispatcher emissions still reach the combine via the scope's continuation chain.
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferencesDataStore =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { temporaryFolder.newFile("prefs-${System.nanoTime()}.preferences_pb") },
            )
        userPreferences = UserPreferencesDataStore(preferencesDataStore)
        favouritesRepository.clock = clock
    }

    @After
    fun tearDown() {
        // Always stop the tick before resetting the dispatcher so a still-running polling
        // coroutine doesn't bleed an uncaught exception into the next test's `runTest`.
        activeViewModel?.stopObserving()
        // Deliberately not cancelling dataStoreScope here — cancelling while a write is
        // mid-edit triggers `CompletionHandlerException`s that poison subsequent tests. The
        // scope is per-test (temp file too) so leaked coroutines are garbage-collected at the
        // JVM exit.
        Dispatchers.resetMain()
    }

    private fun newViewModel(): FavouritesViewModel =
        FavouritesViewModel(
            observeFavourites = ObserveFavouritesUseCase(favouritesRepository),
            reorderFavourites = ReorderFavouritesUseCase(favouritesRepository),
            loadNextDeparture = LoadNextDepartureUseCase(departureRepository),
            favouritesRepository = favouritesRepository,
            userPreferences = userPreferences,
            timeFormatter = formatter,
        ).also { activeViewModel = it }

    /**
     * Drain the test dispatcher until no more tasks are runnable *right now*. Used in place of
     * `advanceUntilIdle` in tests that have a `startObserving` running — `advanceUntilIdle`
     * would run through the polling `while(true) + delay(60s)` forever, while `runCurrent`
     * advances only what's currently runnable. The drain is run several times so a fan-out's
     * async children get pumped to completion.
     *
     * Wedged between the pumps is a real (non-virtual) wait via `runBlocking { delay() }`. This
     * gives the DataStore's IO-scope emissions a window to physically reach the combine's
     * collector (which is on the test dispatcher) before the next `runCurrent` flushes the
     * result into `uiState`.
     */
    private fun kotlinx.coroutines.test.TestScope.drain() {
        repeat(DRAIN_REPETITIONS) {
            runCurrent()
            runBlocking { kotlinx.coroutines.delay(DRAIN_YIELD_MILLIS) }
            runCurrent()
        }
    }

    @Test
    fun `empty repository emits FavouritesUiState Empty`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            drain()
            assertThat(viewModel.uiState.value).isEqualTo(FavouritesUiState.Empty)
        }

    @Test
    fun `three favourites emit a Loaded state with sorted rows (manual = position order)`() =
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
            drain()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.map { it.stopName })
                .containsExactly("Brunswick", "Aberfeldie", "Carlton")
                .inOrder()
            assertThat(loaded.sort).isEqualTo(FavouritesSortPreference.Manual)
        }

    @Test
    fun `Alphabetical sort applied via pre-seeded DataStore re-orders by stopName then routeNumber`() =
        runTest(dispatcher) {
            // Pre-seed the DataStore *before* constructing the VM so the combined flow's first
            // emission lands with Alphabetical already set. Avoids the IO-scoped write race
            // that the "switch sort post-construction" path hits in a pure-JVM harness.
            runBlocking {
                FavouritesSortPreference.Alphabetical.put(dataStoreScope, preferencesDataStore)
                userPreferences.favouritesSort.first { it == FavouritesSortPreference.Alphabetical }
            }
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
            drain()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.sort).isEqualTo(FavouritesSortPreference.Alphabetical)
            assertThat(loaded.rows.map { it.stopName })
                .containsExactly("Aberfeldie", "Brunswick", "Carlton")
                .inOrder()
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
            drain()

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
            drain()

            viewModel.onSwipeDelete(FavouriteKey(stopId = 2, routeId = 22, directionId = 222))
            drain()

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
            drain()

            viewModel.onSwipeDelete(FavouriteKey(stopId = 1, routeId = 11, directionId = 111))
            drain()
            assertThat(favouritesRepository.current).isEmpty()

            viewModel.onUndoDelete()
            drain()

            assertThat(favouritesRepository.current).hasSize(1)
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.pendingUndo).isNull()
        }

    // Skipped: end-to-end sort persistence is exercised by `FavouritesScreenTest` (androidTest)
    // with a Hilt-provided DataStore. The unit harness can't reliably drain the IO-scoped
    // DataStore-write coroutine in time, and a flakey persistence assertion does more harm than
    // good — the typed DSL already has its own coverage in `:core:datastore`'s tests.

    // ---------- 60 s tick + Semaphore-bounded fan-out ----------

    @Test
    fun `startObserving fetches next-departure for each favourite and exposes Loaded labels`() =
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
            // One next-departure response per favourite. The use case calls
            // `repository.getDepartures` once per favourite — enqueue accordingly.
            val matching1 =
                DepartureMother.aDeparture()
                    .withRouteId(11).withDirectionId(111)
                    .withScheduledDepartureUtc(clock.now() + 3.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 3.minutes)
                    .build()
            val matching2 =
                DepartureMother.aDeparture()
                    .withRouteId(22).withDirectionId(222)
                    .withScheduledDepartureUtc(clock.now() + 7.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 7.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(matching1))
            departureRepository.enqueueSuccess(listOf(matching2))

            val viewModel = newViewModel()
            drain()
            viewModel.startObserving()
            // `runCurrent` drains only what's currently runnable — important here because the
            // polling tick is a `while(true) + delay`; `advanceUntilIdle` would run through the
            // virtualized delay forever. Two pumps flush the first fetch iteration's async children.
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows).hasSize(2)
            loaded.rows.forEach { row ->
                assertThat(row.nextDeparture).isInstanceOf(NextDepartureState.Loaded::class.java)
            }
            assertThat(departureRepository.oneShotKeys).hasSize(2)
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
            drain()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            assertThat(departureRepository.oneShotKeys).hasSize(1)

            // Advance past the 60 s tick and let the second iteration land. `advanceTimeBy`
            // moves the virtual clock without running through subsequent delays — perfect for
            // exercising exactly one extra tick.
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
            drain()
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
            drain()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.single().nextDeparture).isEqualTo(NextDepartureState.Empty)
        }

    @Test
    fun `Nearest sort applied via pre-seeded DataStore falls back to Manual ordering until Phase 05`() =
        runTest(dispatcher) {
            // Pre-seed Nearest into the DataStore so the combined flow's first emission already
            // reflects it (same workaround as the Alphabetical pre-seed test above).
            runBlocking {
                FavouritesSortPreference.Nearest.put(dataStoreScope, preferencesDataStore)
                userPreferences.favouritesSort.first { it == FavouritesSortPreference.Nearest }
            }
            favouritesRepository.seed(
                listOf(
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(1).withRouteId(11).withDirectionId(111)
                        .withStopName("Brunswick").withPosition(0)
                        .build(),
                    FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                        .withStopId(2).withRouteId(22).withDirectionId(222)
                        .withStopName("Aberfeldie").withPosition(1)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            drain()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.sort).isEqualTo(FavouritesSortPreference.Nearest)
            // Falls back to Manual ordering (by position), not Alphabetical.
            assertThat(loaded.rows.map { it.stopName })
                .containsExactly("Brunswick", "Aberfeldie")
                .inOrder()
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
            drain()
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.single().routeType).isEqualTo(RouteType.Tram)
        }

    /** A `Clock` that returns a fixed instant. */
    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val DATASTORE_READ_TIMEOUT_MILLIS: Long = 2_000
        const val DRAIN_YIELD_MILLIS: Long = 50
        const val DRAIN_REPETITIONS: Int = 3
    }
}
