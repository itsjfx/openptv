package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.data.test.FakeStopDetailRepository
import ac.jfx.openptv.core.domain.GetStopDetailUseCase
import ac.jfx.openptv.core.domain.LoadMoreDeparturesUseCase
import ac.jfx.openptv.core.domain.ObserveDeparturesUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ToggleFavouriteUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteDestinationAtStopMother
import ac.jfx.openptv.core.testing.RouteMother
import ac.jfx.openptv.core.testing.StopDetailMother
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [StopDetailViewModel]. Uses [StandardTestDispatcher] (so we control when
 * coroutines run via `advanceUntilIdle`), Turbine for state-flow assertions, the hand-written
 * fakes, and a `FakeClock` so `asOf` timestamps land at known instants.
 *
 * Issue #137: favourites are destination-keyed `(stopId, destinationKey)`. Both single-route and
 * multi-route blocks expose a star — the star toggle no longer cares about how many routes feed
 * the destination, only the destination name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class StopDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stopDetailRepository = FakeStopDetailRepository()
    private val departureRepository = FakeDepartureRepository()
    private val favouritesRepository = FakeFavouritesRepository()
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

    private fun newViewModel(
        stopId: Int = DEFAULT_STOP_ID,
        routeTypeCode: Int = RouteType.Train.toCode(),
        focusDestinationKey: String = "",
    ): StopDetailViewModel =
        StopDetailViewModel(
            stopIdValue = stopId,
            routeTypeCode = routeTypeCode,
            focusDestinationKeyValue = focusDestinationKey,
            getStopDetail = GetStopDetailUseCase(stopDetailRepository),
            observeDepartures = ObserveDeparturesUseCase(departureRepository),
            loadMoreDepartures = LoadMoreDeparturesUseCase(departureRepository),
            observeFavourites = ObserveFavouritesUseCase(favouritesRepository),
            toggleFavourite = ToggleFavouriteUseCase(favouritesRepository),
            clock = clock,
            timeFormatter = formatter,
        )

    @Test
    fun `initial state is the canonical Initial value`() {
        stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
        val viewModel = newViewModel()
        assertThat(viewModel.uiState.value).isEqualTo(StopDetailUiState.Initial)
    }

    @Test
    fun `header resolves to Loaded once the use case returns`() =
        runTest(dispatcher) {
            val detail = StopDetailMother.aStopDetail().build()
            stopDetailRepository.enqueueSuccess(detail)
            val viewModel = newViewModel()

            advanceUntilIdle()

            assertThat(viewModel.uiState.value.header).isEqualTo(HeaderState.Loaded(detail))
        }

    @Test
    fun `header Error renders the user-facing reason and retry restores Loading`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueError(IOException("offline"))
            val viewModel = newViewModel()

            advanceUntilIdle()

            val errorState = viewModel.uiState.value.header
            assertThat(errorState).isInstanceOf(HeaderState.Error::class.java)
            assertThat((errorState as HeaderState.Error).reason).contains("network")

            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            viewModel.retryHeader()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.header).isInstanceOf(HeaderState.Loaded::class.java)
        }

    @Test
    fun `observeDepartures emission populates groups, asOf and clears isRefreshing`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(StopDetailUiState.Initial)
                advanceUntilIdle()
                val headerLoaded = awaitItem()
                assertThat(headerLoaded.header).isInstanceOf(HeaderState.Loaded::class.java)

                viewModel.startObserving()
                advanceUntilIdle()

                departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
                advanceUntilIdle()

                val tick = awaitItem()
                assertThat(tick.departures).isInstanceOf(DeparturesState.Loaded::class.java)
                val loaded = tick.departures as DeparturesState.Loaded
                assertThat(loaded.groups).hasSize(1)
                assertThat(loaded.groups.first().departures).hasSize(1)
                assertThat(tick.isRefreshing).isFalse()
                assertThat(tick.asOf).isEqualTo(clock.now())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `empty list emission becomes DeparturesState Empty`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(emptyList())
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.departures).isEqualTo(DeparturesState.Empty)
        }

    @Test
    fun `error mid-poll surfaces DeparturesState Error and next success recovers`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures)
                .isInstanceOf(DeparturesState.Loaded::class.java)

            departureRepository.emitError(IOException("temporary"))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures)
                .isInstanceOf(DeparturesState.Error::class.java)

            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures)
                .isInstanceOf(DeparturesState.Loaded::class.java)
        }

    @Test
    fun `refresh sets isRefreshing then clears it on the next emission`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()
            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.isRefreshing).isFalse()

            viewModel.refresh()
            assertThat(viewModel.uiState.value.isRefreshing).isTrue()

            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.isRefreshing).isFalse()
        }

    @Test
    fun `stopObserving cancels the collector so further emissions do not reach UI state`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()
            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            val firstSnapshot = viewModel.uiState.value
            assertThat(firstSnapshot.departures).isInstanceOf(DeparturesState.Loaded::class.java)

            viewModel.stopObserving()
            advanceUntilIdle()

            departureRepository.emitError(IOException("after stop"))
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.departures).isEqualTo(firstSnapshot.departures)
        }

    @Test
    fun `startObserving twice cancels the previous collector and re-subscribes`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.startObserving()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            assertThat(departureRepository.observedKeys).hasSize(2)
        }

    @Test
    fun `loading emission flips departures back to Loading`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures)
                .isInstanceOf(DeparturesState.Loaded::class.java)

            departureRepository.emit(Result.Loading)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures).isEqualTo(DeparturesState.Loading)
        }

    @Test
    fun `groups sort by earliest departure when no favourites and no pin`() =
        runTest(dispatcher) {
            val detail =
                StopDetailMother.aStopDetail()
                    .withServingRoutes(
                        listOf(
                            RouteMother.aRoute().withId(LATE_ROUTE_ID).withName("Hurstbridge").build(),
                            RouteMother.aRoute().withId(EARLY_ROUTE_ID).withName("Mernda").build(),
                        ),
                    )
                    .build()
            stopDetailRepository.enqueueSuccess(detail)

            val later =
                DepartureMother.aDeparture()
                    .withRouteId(LATE_ROUTE_ID)
                    .withDirectionName("Hurstbridge")
                    .withRunRef("RUN-LATE")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .build()
            val earliest =
                DepartureMother.aDeparture()
                    .withRouteId(EARLY_ROUTE_ID)
                    .withDirectionName("Mernda")
                    .withRunRef("RUN-EARLY")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(later, earliest))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.first().key.destination).isEqualTo("mernda")
            assertThat(loaded.groups.last().key.destination).isEqualTo("hurstbridge")
        }

    @Test
    fun `departures with the same destination but different routes collapse into one starrable block`() =
        runTest(dispatcher) {
            // Issue #87 grouping + issue #137 favouriting: Caulfield → "City" via Cranbourne +
            // Pakenham + Frankston collapses into one block AND exposes a star — the favourite is
            // destination-keyed so the multi-route case is now well-defined.
            val cranbourne = RouteMother.aRoute().withId(CRANBOURNE_ROUTE_ID).withNumber("CRA").withName("Cranbourne").build()
            val pakenham = RouteMother.aRoute().withId(PAKENHAM_ROUTE_ID).withNumber("PAK").withName("Pakenham").build()
            val frankston = RouteMother.aRoute().withId(FRANKSTON_ROUTE_ID).withNumber("FKN").withName("Frankston").build()
            stopDetailRepository.enqueueSuccess(
                StopDetailMother.aStopDetail().withServingRoutes(listOf(cranbourne, pakenham, frankston)).build(),
            )

            val cranCity = cityDeparture(CRANBOURNE_ROUTE_ID, runRef = "CRA-1", at = "2026-05-14T09:05:00Z")
            val pakCity = cityDeparture(PAKENHAM_ROUTE_ID, runRef = "PAK-1", at = "2026-05-14T09:08:00Z")
            val cranLater = cityDeparture(CRANBOURNE_ROUTE_ID, runRef = "CRA-2", at = "2026-05-14T09:15:00Z")

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(cranLater, pakCity, cranCity))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(1)
            val cityBlock = loaded.groups.single()
            assertThat(cityBlock.headerLabel).isEqualTo("City")
            assertThat(cityBlock.key.destination).isEqualTo("city")
            assertThat(cityBlock.departures.map { it.runRef.value })
                .containsExactly("CRA-1", "PAK-1", "CRA-2").inOrder()
            assertThat(cityBlock.routes.map { it.id.value })
                .containsExactly(CRANBOURNE_ROUTE_ID, PAKENHAM_ROUTE_ID)
            // Multi-route blocks expose a destination favourite — not suppressed any more.
            assertThat(cityBlock.isFavourite).isFalse()
        }

    @Test
    fun `single-route group still renders a star and toggleFavourite stars it`() =
        runTest(dispatcher) {
            val mernda = RouteMother.aRoute().withId(EARLY_ROUTE_ID).withNumber("MER").withName("Mernda").build()
            stopDetailRepository.enqueueSuccess(
                StopDetailMother.aStopDetail().withServingRoutes(listOf(mernda)).build(),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val departure =
                DepartureMother.aDeparture()
                    .withRouteId(EARLY_ROUTE_ID)
                    .withDirectionName("Mernda")
                    .withRunRef("ONLY-1")
                    .build()
            departureRepository.emitSuccess(listOf(departure))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.single().isFavourite).isFalse()

            viewModel.toggleFavourite(destinationName = "Mernda")
            advanceUntilIdle()

            val afterToggle = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(afterToggle.groups.single().isFavourite).isTrue()
            assertThat(favouritesRepository.current).hasSize(1)
            val onlyFav = favouritesRepository.current.single()
            assertThat(onlyFav.destinationKey).isEqualTo("mernda")
            assertThat(onlyFav.destinationName).isEqualTo("Mernda")
        }

    @Test
    fun `multi-route block toggleFavourite stores ONE destination-keyed favourite`() =
        runTest(dispatcher) {
            // The headline issue-#137 contract. Three lines feed Caulfield → City; one tap of the
            // star inserts a single `(stopId, "city")` favourite that covers all of them.
            val cranbourne = RouteMother.aRoute().withId(CRANBOURNE_ROUTE_ID).withNumber("CRA").withName("Cranbourne").build()
            val pakenham = RouteMother.aRoute().withId(PAKENHAM_ROUTE_ID).withNumber("PAK").withName("Pakenham").build()
            val frankston = RouteMother.aRoute().withId(FRANKSTON_ROUTE_ID).withNumber("FKN").withName("Frankston").build()
            stopDetailRepository.enqueueSuccess(
                StopDetailMother.aStopDetail().withServingRoutes(listOf(cranbourne, pakenham, frankston)).build(),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(
                listOf(
                    cityDeparture(CRANBOURNE_ROUTE_ID, runRef = "CRA-1", at = "2026-05-14T09:05:00Z"),
                    cityDeparture(PAKENHAM_ROUTE_ID, runRef = "PAK-1", at = "2026-05-14T09:08:00Z"),
                    cityDeparture(FRANKSTON_ROUTE_ID, runRef = "FKN-1", at = "2026-05-14T09:12:00Z"),
                ),
            )
            advanceUntilIdle()

            viewModel.toggleFavourite(destinationName = "City")
            advanceUntilIdle()

            assertThat(favouritesRepository.current).hasSize(1)
            val only = favouritesRepository.current.single()
            // The favourite's stopId comes from the loaded header's stop, regardless of how many
            // routes feed the "City" destination block.
            assertThat(only.destinationKey).isEqualTo("city")
            assertThat(only.destinationName).isEqualTo("City")

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.single().isFavourite).isTrue()
        }

    @Test
    fun `existing destination favourite at this stop lights the star on the matching multi-route group`() =
        runTest(dispatcher) {
            // The favourite already exists in the repo when the screen opens — the projection
            // resolves it via the destination key and the multi-route block renders pre-starred.
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID)
                        .withDestinationKey("city")
                        .withDestinationName("City")
                        .build(),
                ),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(
                listOf(
                    cityDeparture(CRANBOURNE_ROUTE_ID, runRef = "CRA-1", at = "2026-05-14T09:05:00Z"),
                    cityDeparture(PAKENHAM_ROUTE_ID, runRef = "PAK-1", at = "2026-05-14T09:08:00Z"),
                ),
            )
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            val city = loaded.groups.single { it.key.destination == "city" }
            assertThat(city.isFavourite).isTrue()
        }

    @Test
    fun `toggleFavourite a second time removes the favourite and flips isFavourite back to false`() =
        runTest(dispatcher) {
            val route =
                RouteMother.aRoute()
                    .withId(FAVE_ROUTE_ID).withNumber("19").withName("North Coburg")
                    .withRouteType(RouteType.Tram).build()
            stopDetailRepository.enqueueSuccess(
                StopDetailMother.aStopDetail().withServingRoutes(listOf(route)).build(),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()
            departureRepository.emitSuccess(
                listOf(
                    DepartureMother.aDeparture()
                        .withRouteId(FAVE_ROUTE_ID)
                        .withDirectionName("North Coburg")
                        .build(),
                ),
            )
            advanceUntilIdle()

            viewModel.toggleFavourite(destinationName = "North Coburg")
            advanceUntilIdle()
            viewModel.toggleFavourite(destinationName = "North Coburg")
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.single().isFavourite).isFalse()
            assertThat(favouritesRepository.current).isEmpty()
        }

    @Test
    fun `toggleFavourite affects only the matching destination when two groups are visible`() =
        runTest(dispatcher) {
            val routeA =
                RouteMother.aRoute()
                    .withId(FAVE_ROUTE_ID).withNumber("19").withName("North Coburg")
                    .withRouteType(RouteType.Tram).build()
            val routeB =
                RouteMother.aRoute()
                    .withId(OTHER_ROUTE_ID).withNumber("96").withName("East Brunswick")
                    .withRouteType(RouteType.Tram).build()
            stopDetailRepository.enqueueSuccess(
                StopDetailMother.aStopDetail().withServingRoutes(listOf(routeA, routeB)).build(),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(
                listOf(
                    DepartureMother.aDeparture().withRouteId(FAVE_ROUTE_ID).withDirectionName("North Coburg").withRunRef("A-1").build(),
                    DepartureMother.aDeparture()
                        .withRouteId(OTHER_ROUTE_ID).withDirectionName("East Brunswick").withRunRef("B-1")
                        .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                        .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                        .build(),
                ),
            )
            advanceUntilIdle()

            viewModel.toggleFavourite(destinationName = "North Coburg")
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            val faveGroup = loaded.groups.first { it.key.destination == "north coburg" }
            val otherGroup = loaded.groups.first { it.key.destination == "east brunswick" }
            assertThat(faveGroup.isFavourite).isTrue()
            assertThat(otherGroup.isFavourite).isFalse()
        }

    // ---------- pinned destination (issue #137) ----------

    @Test
    fun `focusDestinationKey pins the matching group to the top of the Loaded list`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel(focusDestinationKey = "north coburg")
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val other =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID)
                    .withDirectionName("East Brunswick")
                    .withRunRef("OTHER-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:01:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:01:00Z"))
                    .build()
            val matching =
                DepartureMother.aDeparture()
                    .withRouteId(FAVE_ROUTE_ID)
                    .withDirectionName("North Coburg")
                    .withRunRef("MATCH-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .build()
            departureRepository.emitSuccess(listOf(other, matching))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(2)
            val first = loaded.groups.first()
            assertThat(first.key.destination).isEqualTo("north coburg")
            assertThat(first.isPinned).isTrue()
            assertThat(loaded.groups[1].key.destination).isEqualTo("east brunswick")
            assertThat(loaded.groups[1].isPinned).isFalse()
        }

    @Test
    fun `pinned group is not auto-expanded — issue #90`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel(focusDestinationKey = "north coburg")
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val pinned =
                (1..6).map { i ->
                    DepartureMother.aDeparture()
                        .withRouteId(FAVE_ROUTE_ID).withDirectionName("North Coburg").withRunRef("PIN-$i")
                        .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:${10 + i}:00Z"))
                        .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:${10 + i}:00Z"))
                        .build()
                }
            val other =
                (1..6).map { i ->
                    DepartureMother.aDeparture()
                        .withRouteId(OTHER_ROUTE_ID).withDirectionName("East Brunswick").withRunRef("OTHER-$i")
                        .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:0$i:00Z"))
                        .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:0$i:00Z"))
                        .build()
                }
            departureRepository.emitSuccess(pinned + other)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(2)
            val first = loaded.groups.first()
            assertThat(first.isPinned).isTrue()
            assertThat(first.key.destination).isEqualTo("north coburg")
            assertThat(first.expanded).isFalse()
            assertThat(loaded.groups[1].expanded).isFalse()
            viewModel.toggleExpand(first.key)
            advanceUntilIdle()
            val afterToggle = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(afterToggle.groups.first().expanded).isTrue()
        }

    @Test
    fun `pin with no matching group still surfaces every other group as Loaded`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel(focusDestinationKey = "north coburg")
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val other =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID)
                    .withDirectionName("East Brunswick")
                    .withRunRef("OTHER-1")
                    .build()
            departureRepository.emitSuccess(listOf(other))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(1)
            assertThat(loaded.groups.single().isPinned).isFalse()
        }

    @Test
    fun `no focus filter renders every group as before`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val a =
                DepartureMother.aDeparture()
                    .withRouteId(FAVE_ROUTE_ID).withDirectionName("North Coburg").withRunRef("A-1").build()
            val b =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID).withDirectionName("East Brunswick").withRunRef("B-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .build()
            departureRepository.emitSuccess(listOf(a, b))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(2)
        }

    @Test
    fun `toggleFavourite is a no-op when the header has not loaded yet`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueError(IOException("never resolves"))
            val viewModel = newViewModel()

            viewModel.toggleFavourite(destinationName = "North Coburg")
            advanceUntilIdle()

            assertThat(favouritesRepository.current).isEmpty()
        }

    // ---------- pin favourites to top (issue #100) — destination-keyed ----------

    @Test
    fun `single favourite at the stop pins above non-favourited groups regardless of next-departure time`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID)
                        .withDestinationKey("north coburg")
                        .withDestinationName("North Coburg")
                        .build(),
                ),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val nonFave =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID).withDirectionName("East Brunswick").withRunRef("NON-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:01:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:01:00Z"))
                    .build()
            val fave =
                DepartureMother.aDeparture()
                    .withRouteId(FAVE_ROUTE_ID).withDirectionName("North Coburg").withRunRef("FAV-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .build()
            departureRepository.emitSuccess(listOf(nonFave, fave))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(2)
            assertThat(loaded.groups[0].key.destination).isEqualTo("north coburg")
            assertThat(loaded.groups[0].isFavourite).isTrue()
            assertThat(loaded.groups[1].key.destination).isEqualTo("east brunswick")
            assertThat(loaded.groups[1].isFavourite).isFalse()
        }

    @Test
    fun `multiple favourites order deterministically by destination key`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID).withDestinationKey("werribee").withDestinationName("Werribee").build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID).withDestinationKey("sunbury").withDestinationName("Sunbury").build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID).withDestinationKey("craigieburn").withDestinationName("Craigieburn").build(),
                ),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            // Departures scheduled in reverse-alphabetical order, so the destination-key sort wins
            // over the earliest-departure sort.
            val nonFave =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID).withDirectionName("Footscray").withRunRef("NON-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:01:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:01:00Z"))
                    .build()
            val w =
                DepartureMother.aDeparture()
                    .withRouteId(101).withDirectionName("Werribee").withRunRef("W")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .build()
            val s =
                DepartureMother.aDeparture()
                    .withRouteId(102).withDirectionName("Sunbury").withRunRef("S")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:20:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:20:00Z"))
                    .build()
            val c =
                DepartureMother.aDeparture()
                    .withRouteId(103).withDirectionName("Craigieburn").withRunRef("C")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .build()
            departureRepository.emitSuccess(listOf(nonFave, w, s, c))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(4)
            assertThat(loaded.groups.map { it.key.destination })
                .containsExactly("craigieburn", "sunbury", "werribee", "footscray").inOrder()
            assertThat(loaded.groups[0].isFavourite).isTrue()
            assertThat(loaded.groups[1].isFavourite).isTrue()
            assertThat(loaded.groups[2].isFavourite).isTrue()
            assertThat(loaded.groups[3].isFavourite).isFalse()
        }

    @Test
    fun `selected favourite hoists above other favourites even when its key sorts later`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID).withDestinationKey("craigieburn").withDestinationName("Craigieburn").build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(DEFAULT_STOP_ID).withDestinationKey("werribee").withDestinationName("Werribee").build(),
                ),
            )

            val viewModel = newViewModel(focusDestinationKey = "werribee")
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val faveLow =
                DepartureMother.aDeparture()
                    .withRouteId(101).withDirectionName("Craigieburn").withRunRef("F-C")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .build()
            val faveHigh =
                DepartureMother.aDeparture()
                    .withRouteId(102).withDirectionName("Werribee").withRunRef("F-W")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .build()
            val nonFave =
                DepartureMother.aDeparture()
                    .withRouteId(OTHER_ROUTE_ID).withDirectionName("Footscray").withRunRef("NON")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:02:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:02:00Z"))
                    .build()
            departureRepository.emitSuccess(listOf(faveLow, faveHigh, nonFave))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups).hasSize(3)
            assertThat(loaded.groups[0].key.destination).isEqualTo("werribee")
            assertThat(loaded.groups[0].isPinned).isTrue()
            assertThat(loaded.groups[0].isFavourite).isTrue()
            assertThat(loaded.groups[1].key.destination).isEqualTo("craigieburn")
            assertThat(loaded.groups[1].isPinned).isFalse()
            assertThat(loaded.groups[1].isFavourite).isTrue()
            assertThat(loaded.groups[2].key.destination).isEqualTo("footscray")
            assertThat(loaded.groups[2].isFavourite).isFalse()
        }

    @Test
    fun `no favourites at this stop leaves the existing earliest-departure ordering intact`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val later =
                DepartureMother.aDeparture()
                    .withRouteId(LATE_ROUTE_ID).withDirectionName("Hurstbridge").withRunRef("LATE")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .build()
            val earlier =
                DepartureMother.aDeparture()
                    .withRouteId(EARLY_ROUTE_ID).withDirectionName("Mernda").withRunRef("EARLY")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .build()
            departureRepository.emitSuccess(listOf(later, earlier))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.map { it.key.destination })
                .containsExactly("mernda", "hurstbridge").inOrder()
        }

    @Test
    fun `favourites at a different stop are ignored by the projection`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(OTHER_STOP_ID)
                        .withDestinationKey("north coburg")
                        .withDestinationName("North Coburg")
                        .build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val matching =
                DepartureMother.aDeparture()
                    .withRouteId(FAVE_ROUTE_ID).withDirectionName("North Coburg").build()
            departureRepository.emitSuccess(listOf(matching))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            // Same destination at a different stop — the favourite must not light up here.
            assertThat(loaded.groups.single().isFavourite).isFalse()
        }

    // ---------- existing infrastructure tests (unchanged contracts) ----------

    @Test
    fun `the head poll's upcoming entries are passed through verbatim`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

            val nowish =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-NOW")
                    .withScheduledDepartureUtc(clock.now())
                    .withEstimatedDepartureUtc(clock.now() - 20.seconds)
                    .build()
            val upcoming =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-FUTURE")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(nowish, upcoming))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            val runRefs = loaded.groups.flatMap { it.departures }.map { it.runRef.value }
            assertThat(runRefs).containsExactly("OPS-NOW", "OPS-FUTURE")
        }

    @Test
    fun `paged entries whose live estimate slips past the grace window are GC'd from the cache`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

            val initialHead =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-HEAD")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            val stalePage =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-STALE-PAGE")
                    .withScheduledDepartureUtc(clock.now() + 10.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 3.minutes)
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(initialHead))
            advanceUntilIdle()

            departureRepository.enqueueLoadMoreSuccess(listOf(stalePage))
            viewModel.loadMore()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(initialHead))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.flatMap { it.departures }.map { it.runRef.value })
                .containsExactly("OPS-HEAD")
        }

    // ---------- paging (issues #68 + #69) ----------

    @Test
    fun `groups are collapsed by default — expanded flag is false`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.single().expanded).isFalse()
        }

    @Test
    fun `toggleExpand flips the group expanded flag and triggers a loadMore page fetch`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val head = listOf(DepartureMother.aDeparture().withRunRef("HEAD-1").build())
            departureRepository.emitSuccess(head)
            advanceUntilIdle()

            val key =
                (viewModel.uiState.value.departures as DeparturesState.Loaded)
                    .groups.first().key

            val pageRow =
                DepartureMother.aDeparture()
                    .withRunRef("PAGE-1")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T10:00:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T10:00:00Z"))
                    .build()
            departureRepository.enqueueLoadMoreSuccess(listOf(pageRow))

            viewModel.toggleExpand(key)
            advanceUntilIdle()

            val after = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(after.groups.first().expanded).isTrue()
            assertThat(departureRepository.loadMoreCalls).hasSize(1)
            val call = departureRepository.loadMoreCalls.single()
            assertThat(call.maxResults).isEqualTo(PAGE_SIZE)
            val head1 = head.first()
            val expectedAnchor = head1.estimatedDepartureUtc ?: head1.scheduledDepartureUtc
            assertThat(call.after).isEqualTo(expectedAnchor)
            val runRefs = after.groups.flatMap { it.departures }.map { it.runRef.value }
            assertThat(runRefs).containsAtLeast("HEAD-1", "PAGE-1")
        }

    @Test
    fun `loadMore coalesces concurrent triggers — second call while one is active is dropped`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()

            viewModel.loadMore()
            viewModel.loadMore()
            advanceUntilIdle()

            assertThat(departureRepository.loadMoreCalls).hasSize(1)
        }

    @Test
    fun `paged departures are merged into the group preserving sort order`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            val headRow =
                DepartureMother.aDeparture()
                    .withRunRef("HEAD-1")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.emitSuccess(listOf(headRow))
            advanceUntilIdle()

            val later1 =
                DepartureMother.aDeparture()
                    .withRunRef("PAGE-1")
                    .withScheduledDepartureUtc(clock.now() + 30.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 30.minutes)
                    .build()
            val later2 =
                DepartureMother.aDeparture()
                    .withRunRef("PAGE-2")
                    .withScheduledDepartureUtc(clock.now() + 1.hours)
                    .withEstimatedDepartureUtc(clock.now() + 1.hours)
                    .build()
            departureRepository.enqueueLoadMoreSuccess(listOf(later2, later1))
            viewModel.loadMore()
            advanceUntilIdle()

            val merged = viewModel.uiState.value.departures as DeparturesState.Loaded
            val runRefsInOrder = merged.groups.single().departures.map { it.runRef.value }
            assertThat(runRefsInOrder).containsExactly("HEAD-1", "PAGE-1", "PAGE-2").inOrder()
        }

    @Test
    fun `loadMore is a no-op when there are no current rows to anchor against`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertThat(departureRepository.loadMoreCalls).isEmpty()
        }

    @Test
    fun `empty head poll resolves to DeparturesState Empty`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(emptyList())
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.departures).isEqualTo(DeparturesState.Empty)
        }

    /** Build a Caulfield-style "to City" departure. */
    private fun cityDeparture(routeId: Int, runRef: String, at: String) =
        DepartureMother.aDeparture()
            .withRouteId(routeId)
            .withDirectionName("City")
            .withRunRef(runRef)
            .withScheduledDepartureUtc(Instant.parse(at))
            .withEstimatedDepartureUtc(Instant.parse(at))
            .build()

    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val DEFAULT_STOP_ID = 1071
        const val CAULFIELD_STOP_ID = 22180
        const val OTHER_STOP_ID = 2222
        const val EARLY_ROUTE_ID = 1
        const val LATE_ROUTE_ID = 2
        const val FAVE_ROUTE_ID = 1881
        const val OTHER_ROUTE_ID = 1882
        const val CRANBOURNE_ROUTE_ID = 7001
        const val PAKENHAM_ROUTE_ID = 7002
        const val FRANKSTON_ROUTE_ID = 7003
    }
}
