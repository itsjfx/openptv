package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouriteJourneysRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.data.test.FakeJourneyPlannerRepository
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteDestinationAtStopMother
import ac.jfx.openptv.core.testing.FavouriteJourneyMother
import ac.jfx.openptv.core.testing.JourneyOptionMother
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Journey-favourites coverage for [FavouritesViewModel] (issue #209), split out of
 * [FavouritesViewModelTest] to keep both classes under detekt's size threshold. Same harness:
 * hand-written fakes, StandardTestDispatcher, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesViewModelJourneyFavouritesTest {
    private val dispatcher = StandardTestDispatcher()
    private val favouritesRepository = FakeFavouritesRepository()
    private val departureRepository = FakeDepartureRepository()
    private val favouriteJourneysRepository = FakeFavouriteJourneysRepository()
    private val journeyPlannerRepository = FakeJourneyPlannerRepository()
    private val clock = FakeClock(Instant.parse("2026-05-14T09:00:00Z"))
    private val formatter = RelativeTimeFormatter(clock)

    private var activeViewModel: FavouritesViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        favouritesRepository.clock = clock
        favouriteJourneysRepository.clock = clock
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
            favouriteJourneysRepository = favouriteJourneysRepository,
            journeyPlannerRepository = journeyPlannerRepository,
            timeFormatter = formatter,
        ).also { activeViewModel = it }

    // ---------- journey favourites (issue #209) ----------

    private val journeyOrigin =
        StopMother.aStop().withId(1162).withName("Richmond Station").withSuburb("Richmond").build()
    private val journeyDestination = StopMother.aStop().build()

    @Test
    fun `journey favourites alone produce Loaded with a journeys section, not Empty`() =
        runTest(dispatcher) {
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.rows).isEmpty()
            val row = loaded.journeyRows.single()
            assertThat(row.origin).isEqualTo(journeyOrigin)
            assertThat(row.destination).isEqualTo(journeyDestination)
            assertThat(row.nextService).isEqualTo(JourneyNextServiceState.Loading)
        }

    @Test
    fun `startObserving fetches the next direct service for each journey favourite`() =
        runTest(dispatcher) {
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            journeyPlannerRepository.enqueueSuccess(
                listOf(JourneyOptionMother.aJourneyOption().build()),
            )

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            assertThat(journeyPlannerRepository.oneShotKeys)
                .containsExactly(journeyOrigin to journeyDestination)
            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val next = loaded.journeyRows.single().nextService as JourneyNextServiceState.Loaded
            assertThat(next.routeBadge).isEqualTo("Lilydale")
            assertThat(next.directionName).isEqualTo("Lilydale")
            assertThat(next.departurePlatform).isEqualTo("4")
            // Mother defaults: estimated departure 09:08, estimated arrival 09:11 → 3 min.
            assertThat(next.durationMinutes).isEqualTo(3)
        }

    @Test
    fun `journey next service is the soonest option, not the first listed`() =
        runTest(dispatcher) {
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            val later =
                JourneyOptionMother.aJourneyOption()
                    .withRunRef("LATER")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withDirectionName("Later Run")
                    .build()
            val sooner =
                JourneyOptionMother.aJourneyOption()
                    .withRunRef("SOONER")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .withDirectionName("Sooner Run")
                    .build()
            journeyPlannerRepository.enqueueSuccess(listOf(later, sooner))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            val next = loaded.journeyRows.single().nextService as JourneyNextServiceState.Loaded
            assertThat(next.directionName).isEqualTo("Sooner Run")
        }

    @Test
    fun `journey favourite with no direct services degrades to Empty inline`() =
        runTest(dispatcher) {
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            journeyPlannerRepository.enqueueSuccess(emptyList())

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.journeyRows.single().nextService)
                .isEqualTo(JourneyNextServiceState.Empty)
        }

    @Test
    fun `journey fetch failure surfaces Error inline without breaking the stop rows`() =
        runTest(dispatcher) {
            favouritesRepository.seed(
                listOf(
                    FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                        .withStopId(1).withDestinationKey("city").withDestinationName("City").withPosition(0).build(),
                ),
            )
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            departureRepository.enqueueSuccess(
                listOf(
                    DepartureMother.aDeparture()
                        .withDirectionName("City")
                        .withScheduledDepartureUtc(clock.now() + 5.minutes)
                        .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                        .build(),
                ),
            )
            journeyPlannerRepository.enqueueError(java.io.IOException("proxy down"))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.journeyRows.single().nextService)
                .isEqualTo(JourneyNextServiceState.Error)
            assertThat(loaded.rows.single().nextDeparture)
                .isInstanceOf(NextDepartureState.Loaded::class.java)
        }

    @Test
    fun `journey fetch error after a success keeps the previous Loaded (no flicker)`() =
        runTest(dispatcher) {
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            journeyPlannerRepository.enqueueSuccess(
                listOf(JourneyOptionMother.aJourneyOption().build()),
            )
            journeyPlannerRepository.enqueueError(java.io.IOException("blip"))

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            runCurrent()
            runCurrent()

            advanceTimeBy(61.seconds.inWholeMilliseconds)
            runCurrent()
            runCurrent()
            viewModel.stopObserving()

            val loaded = viewModel.uiState.value as FavouritesUiState.Loaded
            assertThat(loaded.journeyRows.single().nextService)
                .isInstanceOf(JourneyNextServiceState.Loaded::class.java)
        }

    @Test
    fun `the pinned custom time threads into the journey fetch anchor`() =
        runTest(dispatcher) {
            favouriteJourneysRepository.seed(
                listOf(
                    FavouriteJourneyMother.aFavouriteJourney()
                        .withOrigin(journeyOrigin).withDestination(journeyDestination).build(),
                ),
            )
            val viewModel = newViewModel()
            advanceUntilIdle()

            val chosen = Instant.parse("2026-05-15T08:00:00Z")
            journeyPlannerRepository.enqueueSuccess(
                listOf(JourneyOptionMother.aJourneyOption().build()),
            )
            viewModel.setSelectedTime(chosen)
            advanceUntilIdle()

            assertThat(journeyPlannerRepository.lastOneShotAt).isEqualTo(chosen)
        }

    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
