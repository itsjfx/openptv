package ac.jfx.openptv.feature.journeyplanner

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeFavouriteJourneysRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.data.test.FakeFollowedTripRepository
import ac.jfx.openptv.core.data.test.FakeJourneyPlannerRepository
import ac.jfx.openptv.core.data.test.FakeStopSearchRepository
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.testing.AlightAlertMother
import ac.jfx.openptv.core.testing.FavouriteDestinationAtStopMother
import ac.jfx.openptv.core.testing.FollowedTripMother
import ac.jfx.openptv.core.testing.JourneyOptionMother
import ac.jfx.openptv.core.testing.StopMother
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * ViewModel coverage for the journey planner (issue #204): the inline picker pipeline, endpoint
 * selection/swap, the live-vs-pinned-time fetch split, and error/retry. Fakes from
 * `:core:data-test`, fixtures from `:core:testing`, virtual time throughout — the debounce is
 * crossed with `advanceTimeBy(350)` exactly like [SearchViewModelTest] in `:feature:search`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One test class per ViewModel — same trade as NearbyViewModelTest.
class JourneyPlannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val journeys = FakeJourneyPlannerRepository()
    private val search = FakeStopSearchRepository()
    private val favourites = FakeFavouritesRepository()
    private val favouriteJourneys = FakeFavouriteJourneysRepository()
    private val followedTrips = FakeFollowedTripRepository()
    private val fixedNow = Instant.parse("2026-05-14T09:00:00Z")
    private lateinit var viewModel: JourneyPlannerViewModel

    private val richmond = StopMother.aStop().withId(1162).withName("Richmond Station").build()
    private val burnley = StopMother.aStop().withId(1030).withName("Burnley Station").build()
    private val bourkeSt = StopMother.aTramStop().withId(2500).withName("Bourke St / Spencer St").build()
    private val mysteryStop =
        StopMother.aStop().withId(9999).withName("Mystery Stop").withRouteType(RouteType.Unknown).build()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel =
            JourneyPlannerViewModel(
                journeyPlannerRepository = journeys,
                stopSearchRepository = search,
                favouritesRepository = favourites,
                favouriteJourneysRepository = favouriteJourneys,
                followedTripRepository = followedTrips,
                clock = FixedClock(fixedNow),
                timeFormatter = RelativeTimeFormatter(FixedClock(fixedNow)),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is fully idle`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.origin).isNull()
                assertThat(state.destination).isNull()
                assertThat(state.activeField).isNull()
                assertThat(state.picker).isEqualTo(StopPickerState.Idle())
                assertThat(state.results).isEqualTo(JourneyResultsState.Idle)
                assertThat(state.isFavouriteJourney).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `typing in the picker debounces then searches`() =
        runTest(dispatcher) {
            search.enqueueSuccess(listOf(richmond))
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onQueryChanged("rich")
                advanceTimeBy(350)
                advanceUntilIdle()
                val state = awaitUntil { it.picker is StopPickerState.Results }
                assertThat((state.picker as StopPickerState.Results).stops).containsExactly(richmond)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(search.requestedTerms).containsExactly("rich")
        }

    @Test
    fun `picking a stop fills the active field and closes the picker`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                advanceUntilIdle()
                viewModel.onStopPicked(richmond)
                advanceUntilIdle()
                val state = awaitUntil { it.origin != null }
                assertThat(state.origin).isEqualTo(richmond)
                assertThat(state.activeField).isNull()
                assertThat(state.query).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `both endpoints chosen starts the live journeys poll and maps Loaded`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                advanceUntilIdle()
                journeys.emitSuccess(listOf(option))
                advanceUntilIdle()
                val state = awaitUntil { it.results is JourneyResultsState.Loaded }
                assertThat((state.results as JourneyResultsState.Loaded).options).containsExactly(option)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).containsExactly(richmond to burnley)
            assertThat(journeys.lastObservedAt).isNull()
        }

    @Test
    fun `empty journey list maps to NoDirectServices`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                advanceUntilIdle()
                journeys.emitSuccess(emptyList())
                advanceUntilIdle()
                val state = awaitUntil { it.results != JourneyResultsState.Idle }
                assertThat(state.results).isEqualTo(JourneyResultsState.NoDirectServices)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pinned custom time uses the one-shot fetch with the anchor`() =
        runTest(dispatcher) {
            val pinned = Instant.parse("2026-05-15T18:00:00Z")
            journeys.enqueueSuccess(listOf(JourneyOptionMother.aJourneyOption().build()))
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                viewModel.onTimeSelected(pinned)
                advanceUntilIdle()
                val state = awaitUntil { it.results is JourneyResultsState.Loaded }
                assertThat(state.selectedTime).isEqualTo(pinned)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.oneShotKeys).containsExactly(richmond to burnley)
            assertThat(journeys.lastOneShotAt).isEqualTo(pinned)
        }

    @Test
    fun `clearing the pinned time returns to the live poll`() =
        runTest(dispatcher) {
            journeys.enqueueSuccess(emptyList())
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                viewModel.onTimeSelected(Instant.parse("2026-05-15T18:00:00Z"))
                advanceUntilIdle()
                viewModel.onTimeCleared()
                advanceUntilIdle()
                val state = awaitUntil { it.selectedTime == null }
                assertThat(state.selectedTime).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            // One-shot for the pinned window, then a fresh live subscription.
            assertThat(journeys.oneShotKeys).hasSize(1)
            assertThat(journeys.observedKeys).isNotEmpty()
        }

    @Test
    fun `swap exchanges origin and destination and re-subscribes`() =
        runTest(dispatcher) {
            // Plain collector rather than Turbine: StateFlow conflation across the two
            // advanceUntilIdle windows makes the exact emission count nondeterministic here;
            // asserting the settled end state is what the behaviour actually promises. Same
            // trade as the cancellation tests in DepartureRepositoryImplTest.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            advanceUntilIdle()
            viewModel.onSwapStops()
            advanceUntilIdle()

            val settled = states.last()
            assertThat(settled.origin).isEqualTo(burnley)
            assertThat(settled.destination).isEqualTo(richmond)
            assertThat(journeys.observedKeys)
                .containsExactly(richmond to burnley, burnley to richmond)
                .inOrder()
            job.cancel()
        }

    @Test
    fun `clearing one endpoint returns results to Idle and keeps the other endpoint`() =
        runTest(dispatcher) {
            // Plain collector for the same StateFlow-conflation reason as the swap test.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            // Pinning a time triggers the one-shot fetch — seed it or the fake fails loud.
            journeys.enqueueSuccess(emptyList())
            viewModel.onTimeSelected(Instant.parse("2026-05-15T18:00:00Z"))
            advanceUntilIdle()
            viewModel.onStopCleared(JourneyField.Origin)
            advanceUntilIdle()

            val settled = states.last()
            assertThat(settled.origin).isNull()
            assertThat(settled.destination).isEqualTo(burnley)
            // The pinned time survives the clear; only the endpoint resets.
            assertThat(settled.selectedTime).isEqualTo(Instant.parse("2026-05-15T18:00:00Z"))
            assertThat(settled.results).isEqualTo(JourneyResultsState.Idle)
            job.cancel()
        }

    @Test
    fun `clearing the destination stops the live subscription without new fetches`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            advanceUntilIdle()
            val subscriptionsBeforeClear = journeys.observedKeys.size
            viewModel.onStopCleared(JourneyField.Destination)
            advanceUntilIdle()

            val settled = states.last()
            assertThat(settled.destination).isNull()
            assertThat(settled.origin).isEqualTo(richmond)
            assertThat(settled.results).isEqualTo(JourneyResultsState.Idle)
            // A missing endpoint short-circuits to Idle — no repository call for a half pair.
            assertThat(journeys.observedKeys).hasSize(subscriptionsBeforeClear)
            assertThat(journeys.oneShotKeys).isEmpty()
            job.cancel()
        }

    @Test
    fun `repository error maps to a user-facing reason and retry re-subscribes`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                pickBothStops()
                advanceUntilIdle()
                journeys.emitError(IOException("socket bad"))
                advanceUntilIdle()
                val errored = awaitUntil { it.results is JourneyResultsState.Error }
                assertThat((errored.results as JourneyResultsState.Error).reason)
                    .contains("network")

                viewModel.onRetry()
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).hasSize(2)
        }

    @Test
    fun `results stay Idle until both endpoints are chosen`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                advanceUntilIdle()
                viewModel.onStopPicked(richmond)
                advanceUntilIdle()
                val state = awaitUntil { it.origin != null }
                assertThat(state.results).isEqualTo(JourneyResultsState.Idle)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).isEmpty()
            assertThat(journeys.oneShotKeys).isEmpty()
        }

    @Test
    fun `opening the picker with an empty query surfaces favourite stops distinct by stop`() =
        runTest(dispatcher) {
            // Two favourites at Richmond (different destinations) + one at Flinders → two
            // distinct picker rows (issue #209).
            favourites.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1162).withStopName("Richmond Station")
                        .withDestinationKey("city").withPosition(0).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1162).withStopName("Richmond Station")
                        .withDestinationKey("belgrave").withPosition(1).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1071).withStopName("Flinders Street Railway Station")
                        .withDestinationKey("north coburg").withPosition(2).build(),
                ),
            )
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                advanceUntilIdle()
                val state = awaitUntil { (it.picker as? StopPickerState.Idle)?.favouriteStops?.isNotEmpty() == true }
                val stops = (state.picker as StopPickerState.Idle).favouriteStops
                assertThat(stops.map { it.id.value }).containsExactly(1162, 1071).inOrder()
                assertThat(stops.first().name).isEqualTo("Richmond Station")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `picker search with no chips selected requests all modes`() =
        runTest(dispatcher) {
            search.enqueueSuccess(listOf(richmond))
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onQueryChanged("rich")
                advanceTimeBy(350)
                advanceUntilIdle()
                awaitUntil { it.picker is StopPickerState.Results }
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(search.requestedRouteTypes).containsExactly(emptySet<RouteType>())
        }

    @Test
    fun `toggling a chip re-runs the current picker search immediately with the filter on the wire`() =
        runTest(dispatcher) {
            search.enqueueSuccess(listOf(richmond, burnley))
            search.enqueueSuccess(listOf(richmond))
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onQueryChanged("rich")
                advanceTimeBy(350)
                advanceUntilIdle()
                awaitUntil { (it.picker as? StopPickerState.Results)?.stops?.size == 2 }

                viewModel.onRouteTypeFilterToggled(RouteType.Train)
                // Well inside the 300 ms debounce window — the filter combines in *after* the
                // debounce, so the re-query must not wait for it.
                advanceTimeBy(50)
                advanceUntilIdle()
                val state = awaitUntil { (it.picker as? StopPickerState.Results)?.stops?.size == 1 }
                assertThat(state.routeTypeFilter).containsExactly(RouteType.Train)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(search.requestedTerms).containsExactly("rich", "rich").inOrder()
            assertThat(search.requestedRouteTypes)
                .containsExactly(emptySet<RouteType>(), setOf(RouteType.Train))
                .inOrder()
        }

    @Test
    fun `chip filter narrows the favourite-stops idle list client-side and widens back on deselect`() =
        runTest(dispatcher) {
            favourites.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1162).withStopName("Richmond Station")
                        .withRouteType(RouteType.Train).withPosition(0).build(),
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(2500).withStopName("Bourke St / Spencer St")
                        .withRouteType(RouteType.Tram).withPosition(1).build(),
                ),
            )
            // Plain collector rather than Turbine — the same StateFlow-conflation trade as the
            // swap test above: each advanceUntilIdle window settles the state we assert on.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            advanceUntilIdle()
            assertThat((states.last().picker as StopPickerState.Idle).favouriteStops).hasSize(2)

            viewModel.onRouteTypeFilterToggled(RouteType.Train)
            advanceUntilIdle()
            val filtered = (states.last().picker as StopPickerState.Idle).favouriteStops
            assertThat(filtered.map { it.id.value }).containsExactly(1162)

            viewModel.onRouteTypeFilterToggled(RouteType.Train)
            advanceUntilIdle()
            assertThat((states.last().picker as StopPickerState.Idle).favouriteStops).hasSize(2)
            // Favourites are local — no network search was ever issued.
            assertThat(search.requestedTerms).isEmpty()
            job.cancel()
        }

    @Test
    fun `toggling Unknown is a no-op`() =
        runTest(dispatcher) {
            // Plain collector so the pipeline is live while the (ignored) toggle lands.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            advanceUntilIdle()
            viewModel.onRouteTypeFilterToggled(RouteType.Unknown)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).isEmpty()
            job.cancel()
        }

    @Test
    fun `opening the picker defaults the chips to the other endpoint's mode`() =
        runTest(dispatcher) {
            // Plain collector — see the swap test's conflation note.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            viewModel.onStopPicked(richmond)
            advanceUntilIdle()

            viewModel.onFieldSelected(JourneyField.Destination)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Train)
            job.cancel()
        }

    @Test
    fun `the default overwrites a prior session chip selection`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            viewModel.onStopPicked(richmond)
            // A leftover session selection (issue #213 stickiness) from earlier browsing.
            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Tram)

            viewModel.onFieldSelected(JourneyField.Destination)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Train)
            job.cancel()
        }

    @Test
    fun `the default scopes the picker's wire search to the other endpoint's mode`() =
        runTest(dispatcher) {
            search.enqueueSuccess(listOf(burnley))
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onStopPicked(richmond)
                viewModel.onFieldSelected(JourneyField.Destination)
                viewModel.onQueryChanged("burn")
                advanceTimeBy(350)
                advanceUntilIdle()
                awaitUntil { it.picker is StopPickerState.Results }
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(search.requestedRouteTypes).containsExactly(setOf(RouteType.Train))
        }

    @Test
    fun `the defaulted chips stay interactive — toggling off widens back to all modes`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            viewModel.onStopPicked(richmond)
            viewModel.onFieldSelected(JourneyField.Destination)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Train)

            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter)
                .containsExactly(RouteType.Train, RouteType.Tram)

            viewModel.onRouteTypeFilterToggled(RouteType.Train)
            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).isEmpty()
            job.cancel()
        }

    @Test
    fun `no other endpoint applies no default — the session selection sticks`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceUntilIdle()

            viewModel.onFieldSelected(JourneyField.Origin)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Tram)
            job.cancel()
        }

    @Test
    fun `an Unknown other endpoint applies no default`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            viewModel.onStopPicked(mysteryStop)
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            advanceUntilIdle()

            viewModel.onFieldSelected(JourneyField.Destination)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Bus)
            job.cancel()
        }

    @Test
    fun `re-picking with both endpoints set and differing modes applies no default`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            // A cross-mode pair is reachable — the results pane just shows NoDirectServices.
            viewModel.onFieldSelected(JourneyField.Origin)
            viewModel.onStopPicked(richmond)
            viewModel.onFieldSelected(JourneyField.Destination)
            viewModel.onStopPicked(bourkeSt)
            // Opening the destination picker defaulted {Train}; make the session selection {Bus}.
            viewModel.onRouteTypeFilterToggled(RouteType.Train)
            viewModel.onRouteTypeFilterToggled(RouteType.Bus)
            advanceUntilIdle()

            viewModel.onFieldSelected(JourneyField.Origin)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Bus)
            job.cancel()
        }

    @Test
    fun `re-picking with both endpoints set and matching modes re-applies the default`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops() // Richmond → Burnley, both Train.
            viewModel.onRouteTypeFilterToggled(RouteType.Tram)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter)
                .containsExactly(RouteType.Train, RouteType.Tram)

            viewModel.onFieldSelected(JourneyField.Origin)
            advanceUntilIdle()
            assertThat(states.last().routeTypeFilter).containsExactly(RouteType.Train)
            job.cancel()
        }

    @Test
    fun `typing replaces the favourite-stops idle state with search results`() =
        runTest(dispatcher) {
            favourites.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop().withStopId(1162).build(),
                ),
            )
            search.enqueueSuccess(listOf(burnley))
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onQueryChanged("burn")
                advanceTimeBy(350)
                advanceUntilIdle()
                val state = awaitUntil { it.picker is StopPickerState.Results }
                assertThat((state.picker as StopPickerState.Results).stops).containsExactly(burnley)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `toggling the favourite journey stars the pair and the star tracks it reactively`() =
        runTest(dispatcher) {
            // Plain collector rather than Turbine — same StateFlow-conflation trade as the swap
            // test above: asserting the settled state after each advanceUntilIdle window is what
            // the behaviour promises.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            advanceUntilIdle()
            assertThat(states.last().isFavouriteJourney).isFalse()

            viewModel.onToggleFavouriteJourney()
            advanceUntilIdle()
            assertThat(states.last().isFavouriteJourney).isTrue()
            assertThat(favouriteJourneys.current.single().origin).isEqualTo(richmond)
            assertThat(favouriteJourneys.current.single().destination).isEqualTo(burnley)

            viewModel.onToggleFavouriteJourney()
            advanceUntilIdle()
            assertThat(states.last().isFavouriteJourney).isFalse()
            assertThat(favouriteJourneys.current).isEmpty()
            job.cancel()
        }

    @Test
    fun `favourite journey is direction-specific — swapping flips the star off`() =
        runTest(dispatcher) {
            // Plain collector — see the swap test's conflation note.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            advanceUntilIdle()
            viewModel.onToggleFavouriteJourney()
            advanceUntilIdle()
            assertThat(states.last().isFavouriteJourney).isTrue()

            viewModel.onSwapStops()
            advanceUntilIdle()

            val swapped = states.last()
            assertThat(swapped.origin).isEqualTo(burnley)
            // A→B is starred; B→A is a different favourite and starts unstarred.
            assertThat(swapped.isFavouriteJourney).isFalse()
            assertThat(favouriteJourneys.current).hasSize(1)
            assertThat(favouriteJourneys.current.single().origin).isEqualTo(richmond)
            job.cancel()
        }

    @Test
    fun `onToggleFavouriteJourney is a no-op until both endpoints are chosen`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onStopPicked(richmond)
                advanceUntilIdle()
                viewModel.onToggleFavouriteJourney()
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(favouriteJourneys.current).isEmpty()
        }

    @Test
    fun `onEndpointsPrefilled sets both stops, closes the picker, and starts the live fetch`() =
        runTest(dispatcher) {
            viewModel.uiState.test {
                awaitItem()
                // Simulate a half-open picker with a pinned time — the prefill must reset both.
                viewModel.onFieldSelected(JourneyField.Origin)
                viewModel.onTimeSelected(Instant.parse("2026-05-15T18:00:00Z"))
                advanceUntilIdle()

                viewModel.onEndpointsPrefilled(newOrigin = richmond, newDestination = burnley)
                advanceUntilIdle()
                val state = awaitUntil { it.origin == richmond && it.destination == burnley }
                assertThat(state.activeField).isNull()
                assertThat(state.query).isEmpty()
                assertThat(state.selectedTime).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(journeys.observedKeys).contains(richmond to burnley)
        }

    @Test
    fun `arming the bell follows the run with an alert at the destination`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            pickBothStops()
            viewModel.armAlightAlert(option)
            advanceUntilIdle()

            val trip = followedTrips.current
            assertThat(trip).isNotNull()
            assertThat(trip!!.runRef).isEqualTo(option.runRef)
            assertThat(trip.routeType).isEqualTo(richmond.routeType)
            assertThat(trip.fromStopId).isEqualTo(richmond.id)
            assertThat(trip.routeLabel).isEqualTo(option.route.displayLabel)
            assertThat(trip.destinationName).isEqualTo(option.direction.name)
            // Seeded with the arrival at the destination — the alert service refreshes it to the
            // true terminus arrival on its first pattern poll.
            assertThat(trip.completesAtUtc).isEqualTo(option.effectiveArrivalUtc)
            assertThat(trip.followedAtUtc).isEqualTo(fixedNow)
            val alert = trip.alightAlert
            assertThat(alert).isNotNull()
            assertThat(alert!!.stopId).isEqualTo(burnley.id)
            assertThat(alert.stopName).isEqualTo(burnley.name)
            assertThat(alert.coordinates)
                .isEqualTo(Coordinates(lat = burnley.latitude, lng = burnley.longitude))
        }

    @Test
    fun `the armed run surfaces in uiState and clears when the destination changes`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            viewModel.armAlightAlert(option)
            advanceUntilIdle()
            assertThat(states.last().alightArmedRunRef).isEqualTo(option.runRef)

            // Swapping re-points the destination at the origin — the stored alert no longer
            // targets it, so no row should render armed. The stored trip itself is untouched.
            viewModel.onSwapStops()
            advanceUntilIdle()
            assertThat(states.last().alightArmedRunRef).isNull()
            assertThat(followedTrips.current).isNotNull()
            job.cancel()
        }

    @Test
    fun `arming reflects a trip followed elsewhere with an alert at the destination`() =
        runTest(dispatcher) {
            // Armed from the run-pattern screen (issue #201) before this screen composed.
            followedTrips.seed(
                FollowedTripMother.aFollowedTrip()
                    .withRunRef("951825")
                    .withAlightAlert(
                        AlightAlertMother.anAlightAlert().withStopId(1030).build(),
                    ).build(),
            )
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            advanceUntilIdle()
            assertThat(states.last().alightArmedRunRef).isEqualTo(RunRef("951825"))
            job.cancel()
        }

    @Test
    fun `tapping the armed bell stops tracking entirely`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            pickBothStops()
            viewModel.armAlightAlert(option)
            advanceUntilIdle()
            assertThat(followedTrips.current).isNotNull()

            viewModel.disarmAlightAlert(option)
            advanceUntilIdle()
            assertThat(followedTrips.current).isNull()
        }

    @Test
    fun `disarm is a no-op when the stored alert targets a different stop`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            val elsewhere =
                FollowedTripMother.aFollowedTrip()
                    .withRunRef(option.runRef.value)
                    .withAlightAlert(AlightAlertMother.anAlightAlert().withStopId(9999).build())
                    .build()
            followedTrips.seed(elsewhere)
            pickBothStops()
            viewModel.disarmAlightAlert(option)
            advanceUntilIdle()
            assertThat(followedTrips.current).isEqualTo(elsewhere)
        }

    @Test
    fun `re-arming a run already followed keeps followedAtUtc and rebuilds the alert`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aJourneyOption().build()
            val earlier = Instant.parse("2026-05-14T08:50:00Z")
            followedTrips.seed(
                FollowedTripMother.aFollowedTrip()
                    .withRunRef(option.runRef.value)
                    .withFollowedAtUtc(earlier)
                    .withAlightAlert(
                        AlightAlertMother.anAlightAlert().withStopId(9999).withApproachFired(true).build(),
                    ).build(),
            )
            pickBothStops()
            viewModel.armAlightAlert(option)
            advanceUntilIdle()

            val trip = followedTrips.current
            assertThat(trip!!.followedAtUtc).isEqualTo(earlier)
            // A fresh alert at the destination — the fire-once latches reset (issue #201 re-arm).
            assertThat(trip.alightAlert!!.stopId).isEqualTo(burnley.id)
            assertThat(trip.alightAlert!!.approachFired).isFalse()
        }

    @Test
    fun `arming while a different run is followed raises the replace confirmation`() =
        runTest(dispatcher) {
            val other = FollowedTripMother.aFollowedTrip().withRunRef("111111").build()
            followedTrips.seed(other)
            val option = JourneyOptionMother.aJourneyOption().build()
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            viewModel.armAlightAlert(option)
            advanceUntilIdle()

            // Nothing written until the user confirms.
            assertThat(states.last().followReplaceCandidate).isEqualTo(other)
            assertThat(followedTrips.current).isEqualTo(other)

            viewModel.confirmReplaceFollow()
            advanceUntilIdle()
            assertThat(states.last().followReplaceCandidate).isNull()
            assertThat(followedTrips.current!!.runRef).isEqualTo(option.runRef)
            assertThat(followedTrips.current!!.alightAlert!!.stopId).isEqualTo(burnley.id)
            job.cancel()
        }

    @Test
    fun `dismissing the replace confirmation leaves the stored trip untouched`() =
        runTest(dispatcher) {
            val other = FollowedTripMother.aFollowedTrip().withRunRef("111111").build()
            followedTrips.seed(other)
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            viewModel.armAlightAlert(JourneyOptionMother.aJourneyOption().build())
            advanceUntilIdle()

            viewModel.dismissReplaceFollow()
            advanceUntilIdle()
            assertThat(states.last().followReplaceCandidate).isNull()
            assertThat(followedTrips.current).isEqualTo(other)

            // The pending option was consumed — a stray confirm can't arm it any more.
            viewModel.confirmReplaceFollow()
            advanceUntilIdle()
            assertThat(followedTrips.current).isEqualTo(other)
            job.cancel()
        }

    @Test
    fun `arming a schedule-only option flips the location prompt until handled`() =
        runTest(dispatcher) {
            val option = JourneyOptionMother.aScheduledOnlyJourneyOption().build()
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            viewModel.armAlightAlert(option)
            advanceUntilIdle()
            assertThat(states.last().alightLocationPromptNeeded).isTrue()

            viewModel.onAlightLocationPromptHandled()
            advanceUntilIdle()
            assertThat(states.last().alightLocationPromptNeeded).isFalse()
            job.cancel()
        }

    @Test
    fun `arming a tram journey prompts for location even with live estimates`() =
        runTest(dispatcher) {
            // Trams carry estimates on the departures feeds this option was derived from, but
            // never on the pattern endpoint the alert service polls (CLAUDE.md quirks) — the
            // GPS fallback matters, so the prompt must fire on mode, not on the estimates.
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            viewModel.onFieldSelected(JourneyField.Origin)
            viewModel.onStopPicked(bourkeSt)
            viewModel.onFieldSelected(JourneyField.Destination)
            viewModel.onStopPicked(StopMother.aTramStop().withId(2600).withName("Spring St").build())
            viewModel.armAlightAlert(JourneyOptionMother.aJourneyOption().build())
            advanceUntilIdle()
            assertThat(states.last().alightLocationPromptNeeded).isTrue()
            job.cancel()
        }

    @Test
    fun `arming an option with live estimates does not prompt for location`() =
        runTest(dispatcher) {
            val states = mutableListOf<JourneyPlannerUiState>()
            val job = viewModel.uiState.onEach { states += it }.launchIn(this)
            advanceUntilIdle()
            pickBothStops()
            viewModel.armAlightAlert(JourneyOptionMother.aJourneyOption().build())
            advanceUntilIdle()
            assertThat(states.last().alightLocationPromptNeeded).isFalse()
            assertThat(followedTrips.current).isNotNull()
            job.cancel()
        }

    private fun pickBothStops() {
        viewModel.onFieldSelected(JourneyField.Origin)
        viewModel.onStopPicked(richmond)
        viewModel.onFieldSelected(JourneyField.Destination)
        viewModel.onStopPicked(burnley)
    }

    private suspend fun ReceiveTurbine<JourneyPlannerUiState>.awaitUntil(
        predicate: (JourneyPlannerUiState) -> Boolean,
    ): JourneyPlannerUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
