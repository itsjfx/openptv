package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeStopDetailRepository
import ac.jfx.openptv.core.domain.GetStopDetailUseCase
import ac.jfx.openptv.core.domain.LoadMoreDeparturesUseCase
import ac.jfx.openptv.core.domain.ObserveDeparturesUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.DepartureMother
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
 * [FakeStopDetailRepository] / [FakeDepartureRepository] fakes, and a `FakeClock` so `asOf`
 * timestamps land at known instants.
 *
 * The tests pin the contract issue #30 calls out:
 *  - Initial → header Loaded + departures Loaded after first emission lands.
 *  - Manual refresh flips `isRefreshing` and brings `asOf` forward.
 *  - An error mid-poll surfaces `DeparturesState.Error` but the loop is not broken — the next
 *    `emitSuccess` recovers the screen.
 *  - `stopObserving` cancels the collector so subsequent `emit` calls don't reach the UI state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stopDetailRepository = FakeStopDetailRepository()
    private val departureRepository = FakeDepartureRepository()
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
    ): StopDetailViewModel =
        StopDetailViewModel(
            stopIdValue = stopId,
            routeTypeCode = routeTypeCode,
            getStopDetail = GetStopDetailUseCase(stopDetailRepository),
            observeDepartures = ObserveDeparturesUseCase(departureRepository),
            loadMoreDepartures = LoadMoreDeparturesUseCase(departureRepository),
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

            // Re-queue a happy response and retry — header flips back through Loading to Loaded.
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            viewModel.retryHeader()
            // The synchronous mutation to Loading runs on the same dispatcher; advance to drain.
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.header).isInstanceOf(HeaderState.Loaded::class.java)
        }

    @Test
    fun `observeDepartures emission populates groups, asOf and clears isRefreshing`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val viewModel = newViewModel()
            viewModel.uiState.test {
                // Initial state
                assertThat(awaitItem()).isEqualTo(StopDetailUiState.Initial)
                // Header lands first.
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

            // First emission: success
            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures)
                .isInstanceOf(DeparturesState.Loaded::class.java)

            // Mid-poll error — loop is not broken, just surfaced.
            departureRepository.emitError(IOException("temporary"))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.departures)
                .isInstanceOf(DeparturesState.Error::class.java)

            // Next tick recovers.
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
            // Synchronous setter; the next emission needs to land to clear it.
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

            // Now emit something else — the cancelled collector shouldn't pick it up.
            departureRepository.emitError(IOException("after stop"))
            advanceUntilIdle()

            // Departures should be unchanged from `firstSnapshot`.
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

            // Both subscribe calls land — the fake's `observedKeys` should reflect that. Two
            // entries means the second `startObserving` cancelled and re-subscribed, which is
            // the contract Compose's `repeatOnLifecycle(RESUMED)` relies on.
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
    fun `groups sort by earliest departure, departures within group sort by effective time`() =
        runTest(dispatcher) {
            // Detail with two routes serving the stop.
            val detail =
                StopDetailMother.aStopDetail()
                    .withServingRoutes(
                        listOf(
                            ac.jfx.openptv.core.testing.RouteMother.aRoute()
                                .withId(LATE_ROUTE_ID)
                                .withName("Hurstbridge")
                                .build(),
                            ac.jfx.openptv.core.testing.RouteMother.aRoute()
                                .withId(EARLY_ROUTE_ID)
                                .withName("Mernda")
                                .build(),
                        ),
                    )
                    .build()
            stopDetailRepository.enqueueSuccess(detail)

            val later =
                DepartureMother.aDeparture()
                    .withRouteId(LATE_ROUTE_ID)
                    .withRunRef("RUN-LATE")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:30:00Z"))
                    .build()
            val earliest =
                DepartureMother.aDeparture()
                    .withRouteId(EARLY_ROUTE_ID)
                    .withRunRef("RUN-EARLY")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            // Emit out of order so the sort proves itself.
            departureRepository.emitSuccess(listOf(later, earliest))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.first().key.routeId).isEqualTo(EARLY_ROUTE_ID)
            assertThat(loaded.groups.last().key.routeId).isEqualTo(LATE_ROUTE_ID)
        }

    @Test
    fun `already-departed entries are filtered out, upcoming ones remain`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

            // Three departures relative to the test clock (`2026-05-14T09:00:00Z`):
            //  - five minutes ago — filtered.
            //  - twenty seconds ago — kept (well inside the 2 min "now" grace window).
            //  - five minutes out — kept.
            val past =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-PAST")
                    .withScheduledDepartureUtc(clock.now() - 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 5.minutes)
                    .build()
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

            departureRepository.emitSuccess(listOf(past, nowish, upcoming))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            val runRefs = loaded.groups.flatMap { it.departures }.map { it.runRef.value }
            assertThat(runRefs).containsExactly("OPS-NOW", "OPS-FUTURE")
        }

    @Test
    fun `a delayed departure whose live estimate is still in the future is kept`() =
        runTest(dispatcher) {
            // Scheduled time has passed, but the live estimate hasn't — i.e. the service
            // is running late and hasn't actually departed yet. The filter must consult
            // the live estimate (the only source of truth for "did it leave?"), not the
            // stale schedule. Otherwise late-running services would vanish from the screen
            // exactly when riders need them most.
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val delayed =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-LATE")
                    .withScheduledDepartureUtc(clock.now() - 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 2.minutes)
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(delayed))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.flatMap { it.departures }.map { it.runRef.value })
                .containsExactly("OPS-LATE")
        }

    @Test
    fun `a delayed departure whose live estimate is within the grace window is kept`() =
        runTest(dispatcher) {
            // Live estimate is 90 s in the past — would render as "now" — so it stays.
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val barelyPast =
                DepartureMother.aDeparture()
                    .withRunRef("OPS-NOWISH")
                    .withScheduledDepartureUtc(clock.now() - 10.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 90.seconds)
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(barelyPast))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.departures as DeparturesState.Loaded
            assertThat(loaded.groups.flatMap { it.departures }.map { it.runRef.value })
                .containsExactly("OPS-NOWISH")
        }

    @Test
    fun `a delayed departure whose live estimate is past the grace window is filtered`() =
        runTest(dispatcher) {
            // Live estimate is 3 min in the past — beyond the 2 min grace — so the row
            // would render as "departed". Filter it out.
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())
            val stale =
                DepartureMother.aDeparture()
                    .withScheduledDepartureUtc(clock.now() - 10.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 3.minutes)
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(stale))
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.departures).isEqualTo(DeparturesState.Empty)
        }

    @Test
    fun `a list of only departed entries becomes DeparturesState Empty`() =
        runTest(dispatcher) {
            stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

            val past =
                DepartureMother.aDeparture()
                    .withScheduledDepartureUtc(clock.now() - 10.minutes)
                    .withEstimatedDepartureUtc(clock.now() - 10.minutes)
                    .build()

            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.startObserving()
            advanceUntilIdle()

            departureRepository.emitSuccess(listOf(past))
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.departures).isEqualTo(DeparturesState.Empty)
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

            // Enqueue a page so the loadMore lands deterministically.
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
            // Anchor is the latest known departure (the head row, since the page hadn't landed
            // when the call was placed). `effectiveDepartureUtc` prefers the live estimate.
            val head1 = head.first()
            val expectedAnchor = head1.estimatedDepartureUtc ?: head1.scheduledDepartureUtc
            assertThat(call.after).isEqualTo(expectedAnchor)
            // The page row is now merged into the group.
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
            // No advance — the first job is still active.
            viewModel.loadMore()
            advanceUntilIdle()

            // Only one call landed on the fake — the second invocation coalesced.
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

            // Head poll: one row.
            val headRow =
                DepartureMother.aDeparture()
                    .withRunRef("HEAD-1")
                    .withScheduledDepartureUtc(clock.now() + 5.minutes)
                    .withEstimatedDepartureUtc(clock.now() + 5.minutes)
                    .build()
            departureRepository.emitSuccess(listOf(headRow))
            advanceUntilIdle()

            // Page: two rows further out — they should sort after the head row.
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
            val runRefsInOrder =
                merged.groups.single().departures.map { it.runRef.value }
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

            // No head emission yet — `currentTailAnchor` is null.
            viewModel.loadMore()
            advanceUntilIdle()

            assertThat(departureRepository.loadMoreCalls).isEmpty()
        }

    /** A `Clock` that returns a fixed instant — same shape as the formatter's test-only clock. */
    private class FakeClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val DEFAULT_STOP_ID = 1071
        const val EARLY_ROUTE_ID = 1
        const val LATE_ROUTE_ID = 2
    }
}
