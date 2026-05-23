package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteDestinationAtStopMother
import ac.jfx.openptv.core.testing.RouteMother
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
 * Unit tests for [FavouritesViewModel] against the hand-written fakes.
 *
 * Issue #137: favourites are destination-keyed `(stopId, destinationKey)`. Next-departure picks
 * the soonest matching service across whatever route happens to operate it, and the row exposes
 * that route's badge alongside the destination label.
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
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withDestinationName("North Coburg")
                        .withStopName("Brunswick").withPosition(0).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(2).withDestinationKey("city").withDestinationName("City")
                        .withStopName("Aberfeldie").withPosition(1).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(3).withDestinationKey("east brunswick").withDestinationName("East Brunswick")
                        .withStopName("Carlton").withPosition(2).build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows.map { it.stopName })
                .containsExactly("Brunswick", "Aberfeldie", "Carlton")
                .inOrder()
            assertThat(loaded.editMode).isFalse()
            assertThat(loaded.isRefreshing).isFalse()
        }

    @Test
    fun `onReorder writes the new pair ordering to the repository`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(2).withDestinationKey("city").withPosition(1).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(3).withDestinationKey("frankston").withPosition(2).build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            // Move row 0 to the tail.
            viewModel.onReorder(
                orderedKeys =
                    listOf(
                        FavouriteKey(2, "city"),
                        FavouriteKey(3, "frankston"),
                        FavouriteKey(1, "north coburg"),
                    ),
            )
            advanceUntilIdle()

            val current = favouritesRepository.current.sortedBy { it.position }
            assertThat(current.map { it.stopId.value }).containsExactly(2, 3, 1).inOrder()
        }

    @Test
    fun `onSwipeDelete removes from repository and stashes pendingUndo with original position`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(2).withDestinationKey("city").withPosition(1).build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.onSwipeDelete(FavouriteKey(stopId = 2, destinationKey = "city"))
            advanceUntilIdle()

            assertThat(favouritesRepository.current.map { it.stopId.value }).containsExactly(1)

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val undo = loaded.pendingUndo
            assertThat(undo).isNotNull()
            assertThat(undo!!.originalPosition).isEqualTo(1)
            assertThat(undo.row.key.stopId).isEqualTo(2)
            assertThat(undo.row.key.destinationKey).isEqualTo("city")
        }

    @Test
    fun `onUndoDelete re-adds the favourite and clears pendingUndo`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.onSwipeDelete(FavouriteKey(stopId = 1, destinationKey = "north coburg"))
            advanceUntilIdle()
            assertThat(favouritesRepository.current).isEmpty()

            viewModel.onUndoDelete()
            advanceUntilIdle()

            assertThat(favouritesRepository.current).hasSize(1)
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.pendingUndo).isNull()
        }

    @Test
    fun `toggleEditMode flips Loaded editMode flag`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
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
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
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

    @Test
    fun `refresh flips isRefreshing and runs an extra fan-out cycle`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                ),
            )
            val dep =
                DepartureMother.aDeparture()
                    .withDirectionName("North Coburg")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(dep))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.isRefreshing).isFalse()
            assertThat(departureRepository.oneShotKeys).hasSize(1)
        }

    @Test
    fun `startObserving fetches next-departure for each favourite and exposes Loaded with the live route badge`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg")
                        .withRouteType(RouteType.Tram).build(),
                ),
            )
            // Real-time tracking lags the timetable — both clock times should land on the row.
            val matching =
                DepartureMother.aDeparture()
                    .withRouteId(19).withDirectionName("North Coburg")
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
            assertThat(next.scheduledUtc).isNotNull()
            assertThat(next.estimatedUtc).isNotNull()
            assertThat(next.estimatedUtc).isNotEqualTo(next.scheduledUtc)
            // Issue #137: the badge reflects the actual next service. With tram routeType +
            // empty routeNumber/routeName from the mother default, the helper falls back to
            // `#<routeId>`.
            assertThat(next.routeBadge).isEqualTo("#19")
            assertThat(next.routeName).isEqualTo("North Coburg")
        }

    @Test
    fun `train badge shows the line name joined from the routes sideload (issue #137 regression)`() =
        runTest(dispatcher) {
            // The regression itself: favouriting a Belgrave train should render "Belgrave" on the
            // row badge, not "#<routeId>". Before the fix the VM derived the badge from the
            // departure alone with blank routeName/routeNumber and the helper fell back to the id.
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1071).withDestinationKey("belgrave").withDestinationName("Belgrave")
                        .withRouteType(RouteType.Train).build(),
                ),
            )
            val belgraveDep =
                DepartureMother.aDeparture()
                    .withRouteId(5).withDirectionName("Belgrave").withRunRef("BEL-1")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            val belgraveRoute = RouteMother.aRoute().withId(5).withName("Belgrave").withRouteType(RouteType.Train).build()
            departureRepository.enqueueSuccessWithRoutes(
                departures = listOf(belgraveDep),
                routes = listOf(belgraveRoute),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val next = loaded.rows.single().nextDeparture as NextDepartureState.Loaded
            assertThat(next.routeBadge).isEqualTo("Belgrave")
        }

    @Test
    fun `multi-route destination badge reflects whichever line is actually next`() =
        runTest(dispatcher) {
            // The headline #137 affordance on the favourites screen: at Caulfield → City, the
            // favourite row shows the badge of the route that's soonest. The badge text is the
            // line name joined from the PTV `routes` sideload (issue #137 regression fix).
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(22180).withDestinationKey("city").withDestinationName("City")
                        .withRouteType(RouteType.Train).build(),
                ),
            )
            val cran =
                DepartureMother.aDeparture()
                    .withRouteId(101).withDirectionName("City").withRunRef("CRA")
                    .withScheduledDepartureUtc(clock.now() + 4.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 4.minutes)
                    .build()
            val pak =
                DepartureMother.aDeparture()
                    .withRouteId(102).withDirectionName("City").withRunRef("PAK")
                    .withScheduledDepartureUtc(clock.now() + 9.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 9.minutes)
                    .build()
            val cranRoute = RouteMother.aRoute().withId(101).withName("Cranbourne").withRouteType(RouteType.Train).build()
            val pakRoute = RouteMother.aRoute().withId(102).withName("Pakenham").withRouteType(RouteType.Train).build()
            departureRepository.enqueueSuccessWithRoutes(
                departures = listOf(pak, cran),
                routes = listOf(cranRoute, pakRoute),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val next = loaded.rows.single().nextDeparture as NextDepartureState.Loaded
            // Cranbourne is sooner — its line name wins.
            assertThat(next.routeBadge).isEqualTo("Cranbourne")
        }

    @Test
    fun `tick re-runs after 60 s and re-queries departures`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                ),
            )
            val dep =
                DepartureMother.aDeparture()
                    .withDirectionName("North Coburg")
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
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                ),
            )
            val dep =
                DepartureMother.aDeparture()
                    .withDirectionName("North Coburg")
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
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0).build(),
                ),
            )
            val otherDest =
                DepartureMother.aDeparture()
                    .withDirectionName("Footscray")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.enqueueSuccess(listOf(otherDest))

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
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg")
                        .withRouteType(RouteType.Tram).withPosition(0).build(),
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
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("north coburg").withPosition(0)
                        .withLat(-37.8183).withLng(144.9671)
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.onSwipeDelete(FavouriteKey(stopId = 1, destinationKey = "north coburg"))
            advanceUntilIdle()
            viewModel.onUndoDelete()
            advanceUntilIdle()

            val restored = favouritesRepository.current.single()
            assertThat(restored.lat).isEqualTo(-37.8183)
            assertThat(restored.lng).isEqualTo(144.9671)
        }

    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
