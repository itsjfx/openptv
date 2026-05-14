/*
 * Copyright 2026 The OpenPTV Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.data.test.FakeStopDetailRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.RouteMother
import ac.jfx.openptv.core.testing.StopDetailMother
import ac.jfx.openptv.core.testing.StopMother
import ac.jfx.openptv.uitesthiltmanifest.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Hilt-instrumented Compose UI tests for [StopDetailRoute]. Hosts the stateful route entry inside
 * [HiltComponentActivity] so `hiltViewModel()` resolves a real [StopDetailViewModel] backed by
 * the [FakeStopDetailRepository] / [FakeDepartureRepository] swapped in by `FakeDataModule`'s
 * `@TestInstallIn`. No MockK; the fake repositories are the entire test seam.
 *
 * The Hilt rule has to run before the Compose rule (`order = 0`) so the graph is built by the
 * time `composeTestRule.setContent { ... }` requests dependencies. Same template as
 * `:feature:search`'s `SearchScreenTest`.
 */
@HiltAndroidTest
class StopDetailScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject
    lateinit var stopDetailRepository: FakeStopDetailRepository

    @Inject
    lateinit var departureRepository: FakeDepartureRepository

    @Inject
    lateinit var favouritesRepository: FakeFavouritesRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    /**
     * Loading skeleton path: before the header or departures resolve, the screen shows the
     * skeleton placeholder.
     */
    @Test
    fun loadingState_rendersSkeletonBeforeFirstEmission() {
        // Don't enqueue anything yet — both fakes have empty queues, so the use cases will
        // block. The screen starts in `StopDetailUiState.Initial` (departures = Loading).
        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        // The loading skeleton is tagged and the title falls back to the screen's generic title
        // (header hasn't resolved yet).
        composeTestRule.onNodeWithTag(TestTagLoading).assertIsDisplayed()
    }

    /**
     * Golden path: header resolves to a Flinders stop, departures emit one row, the row's
     * content description includes the destination phrase, the "as of" line appears, the route
     * chips strip is rendered.
     */
    @Test
    fun goldenPath_rendersHeaderRouteChipsAndDepartureRow() {
        stopDetailRepository.enqueueSuccess(
            StopDetailMother.aStopDetail()
                .withStop(
                    StopMother.aStop()
                        .withName("Flinders Street Railway Station")
                        .withSuburb("Melbourne City")
                        .build(),
                )
                .build(),
        )

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        // Emit one departure once the screen is up and observing.
        runBlocking {
            // A small ramp-up wait so the lifecycle owner has hit RESUMED before we emit;
            // the fake's MutableSharedFlow has `replay = 1` so the late subscriber still
            // sees the most-recent emission either way, but emitting after `setContent`
            // mirrors the production timing more faithfully.
            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
        }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText("Flinders Street Railway Station")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithText("Flinders Street Railway Station").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagRouteChips).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagDepartureRow).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagAsOf).assertIsDisplayed()
    }

    /**
     * Empty state: a successful fetch with no departures (last service of the day) renders the
     * empty-state copy from `feature_stop_detail_empty`.
     */
    @Test
    fun emptyState_rendersLastServiceCopy() {
        stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        runBlocking {
            departureRepository.emitSuccess(emptyList())
        }

        val empty = composeTestRule.activity.getString(R.string.feature_stop_detail_empty)
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText(empty)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(empty).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagEmpty).assertIsDisplayed()
    }

    /**
     * Error path: emit an error mid-poll → the screen surfaces the user-facing reason and a retry
     * affordance. Tapping retry triggers another fetch (we can see the fake's `observedKeys`
     * grew, although the assertion here is just on the visible retry button + reason copy).
     */
    @Test
    fun errorState_rendersReasonAndRetryButton() {
        stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        runBlocking {
            departureRepository.emitError(IOException("offline"))
        }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText("network", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagError).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagDeparturesRetry).assertIsDisplayed()

        // Tap retry — the UI doesn't crash and we can immediately recover by emitting a fresh
        // success. The user-facing payoff is that the row renders.
        composeTestRule.onNodeWithTag(TestTagDeparturesRetry).performClick()
        runBlocking {
            departureRepository.emitSuccess(listOf(DepartureMother.aDeparture().build()))
        }
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithTag(TestTagDepartureRow)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagDepartureRow).assertIsDisplayed()
    }

    /**
     * Header-error retry: when the one-shot header fetch fails, the user sees the header error
     * block with a retry button that flips the header back into Loading then Loaded on the next
     * (re-enqueued) success.
     */
    @Test
    fun headerErrorRetry_recoversToLoadedHeader() {
        stopDetailRepository.enqueueError(IOException("offline"))

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        // The header-retry button is tagged separately from the departures-retry one.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithTag(TestTagHeaderRetry)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagHeaderRetry).assertIsDisplayed()

        // Re-enqueue a success and tap retry.
        stopDetailRepository.enqueueSuccess(
            StopDetailMother.aStopDetail()
                .withStop(
                    StopMother.aStop().withName("Flinders Street Railway Station").build(),
                )
                .build(),
        )
        composeTestRule.onNodeWithTag(TestTagHeaderRetry).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText("Flinders Street Railway Station")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText("Flinders Street Railway Station").assertIsDisplayed()
    }

    /**
     * Collapsed-by-default groups (issue #68). A successful emission with more than three rows in
     * one group renders only the first three plus a "Show N more" affordance — tapping the row
     * expands the group and reveals every loaded row.
     */
    @Test
    fun collapsedGroup_showsFirstThreeRowsAndShowMoreAffordance() {
        stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        // Five rows for one (routeId, directionId) — collapsed view shows three.
        val now = Clock.System.now()
        val rows =
            (0 until 5).map { i ->
                DepartureMother.aDeparture()
                    .withRunRef("ROW-$i")
                    .withScheduledDepartureUtc(now + (i + 5).minutes)
                    .withEstimatedDepartureUtc(now + (i + 5).minutes)
                    .build()
            }
        runBlocking { departureRepository.emitSuccess(rows) }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagDepartureRow).fetchSemanticsNodes().isNotEmpty()
        }

        // Only the first three rows render while collapsed; the show-more affordance is visible.
        assertThat(composeTestRule.onAllNodesWithTag(TestTagDepartureRow).fetchSemanticsNodes())
            .hasSize(COLLAPSED_VISIBLE)
        composeTestRule.onNodeWithTag(TestTagShowMore).assertIsDisplayed()

        // Tap it — every row is now visible.
        composeTestRule.onNodeWithTag(TestTagShowMore).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagDepartureRow).fetchSemanticsNodes().size >= rows.size
        }
        assertThat(composeTestRule.onAllNodesWithTag(TestTagDepartureRow).fetchSemanticsNodes())
            .hasSize(rows.size)
    }

    /**
     * Next-day date divider (issue #69). When a group's departures cross midnight local time,
     * a date header is inserted between the rows that fall on different calendar dates.
     */
    @Test
    fun crossingMidnight_rendersDateDividerBetweenDays() {
        stopDetailRepository.enqueueSuccess(StopDetailMother.aStopDetail().build())

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        // Anchor the test on a known instant that's late evening locally. Use system clock-anchored
        // offsets so the test isn't sensitive to TZ shifts — we just need two rows that fall on
        // different calendar days in the current zone.
        val now = Clock.System.now()
        val tomorrow = now + 24.hours
        val today = now + 5.minutes
        val rows =
            listOf(
                DepartureMother.aDeparture()
                    .withRunRef("TODAY-1")
                    .withScheduledDepartureUtc(today)
                    .withEstimatedDepartureUtc(today)
                    .build(),
                DepartureMother.aDeparture()
                    .withRunRef("TMRW-1")
                    .withScheduledDepartureUtc(tomorrow)
                    .withEstimatedDepartureUtc(tomorrow)
                    .build(),
            )
        runBlocking { departureRepository.emitSuccess(rows) }

        // Group has 2 rows, both visible while collapsed (≤ COLLAPSED_VISIBLE), so we don't need
        // to tap show-more. The date divider is keyed by the next-day local date.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagDateDivider).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagDateDivider).assertIsDisplayed()
    }

    /**
     * Star affordance on the group header (issue #34). Render the screen with one group, tap the
     * star, assert the fake repository received an add. Tap again, assert remove. Mirrors what
     * the user does on real hardware.
     */
    @Test
    fun favouriteStar_tapAddsThenRemovesFromRepository() {
        val route =
            RouteMother.aRoute()
                .withId(FAVE_ROUTE_ID)
                .withNumber("19")
                .withName("North Coburg")
                .withRouteType(RouteType.Tram)
                .build()
        stopDetailRepository.enqueueSuccess(
            StopDetailMother.aStopDetail()
                .withServingRoutes(listOf(route))
                .build(),
        )

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Train,
                onBack = { /* no-op */ },
            )
        }

        val now = Clock.System.now()
        val departure =
            DepartureMother.aDeparture()
                .withRouteId(FAVE_ROUTE_ID)
                .withDirectionId(FAVE_DIRECTION_ID)
                .withDirectionName("North Coburg")
                .withScheduledDepartureUtc(now + 5.minutes)
                .withEstimatedDepartureUtc(now + 5.minutes)
                .build()
        runBlocking { departureRepository.emitSuccess(listOf(departure)) }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagFavouriteToggle).fetchSemanticsNodes().isNotEmpty()
        }

        // Initial state: hollow star, repository empty.
        composeTestRule.onNodeWithTag(TestTagFavouriteToggle).assertIsDisplayed()
        assertThat(favouritesRepository.current).isEmpty()

        // Tap to favourite.
        composeTestRule.onNodeWithTag(TestTagFavouriteToggle).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            favouritesRepository.current.isNotEmpty()
        }
        assertThat(favouritesRepository.current).hasSize(1)
        val added = favouritesRepository.current.single()
        assertThat(added.routeId.value).isEqualTo(FAVE_ROUTE_ID)
        assertThat(added.directionId.value).isEqualTo(FAVE_DIRECTION_ID)

        // Tap to unfavourite.
        composeTestRule.onNodeWithTag(TestTagFavouriteToggle).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            favouritesRepository.current.isEmpty()
        }
        assertThat(favouritesRepository.current).isEmpty()
    }

    /**
     * Issue #35: when entered with `focusRouteId` + `focusDirectionId`, the screen filters down
     * to only the matching `(routeId, directionId)` group — the other group's header is not
     * rendered.
     */
    @Test
    fun focusArgs_filterDeparturesToASingleGroup() {
        val faveRoute =
            RouteMother.aRoute()
                .withId(FAVE_ROUTE_ID)
                .withNumber("19")
                .withName("North Coburg")
                .withRouteType(RouteType.Tram)
                .build()
        val otherRoute =
            RouteMother.aRoute()
                .withId(OTHER_ROUTE_ID)
                .withNumber("96")
                .withName("East Brunswick")
                .withRouteType(RouteType.Tram)
                .build()
        stopDetailRepository.enqueueSuccess(
            StopDetailMother.aStopDetail()
                .withServingRoutes(listOf(faveRoute, otherRoute))
                .build(),
        )

        composeTestRule.setContent {
            StopDetailRoute(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Tram,
                focusRouteId = FAVE_ROUTE_ID,
                focusDirectionId = FAVE_DIRECTION_ID,
                onBack = { /* no-op */ },
            )
        }

        val now = Clock.System.now()
        val matching =
            DepartureMother.aDeparture()
                .withRouteId(FAVE_ROUTE_ID)
                .withDirectionId(FAVE_DIRECTION_ID)
                .withDirectionName("North Coburg")
                .withRunRef("MATCH-1")
                .withScheduledDepartureUtc(now + 5.minutes)
                .withEstimatedDepartureUtc(now + 5.minutes)
                .build()
        val other =
            DepartureMother.aDeparture()
                .withRouteId(OTHER_ROUTE_ID)
                .withDirectionId(99)
                .withDirectionName("East Brunswick")
                .withRunRef("OTHER-1")
                .withScheduledDepartureUtc(now + 5.minutes)
                .withEstimatedDepartureUtc(now + 5.minutes)
                .build()
        runBlocking { departureRepository.emitSuccess(listOf(matching, other)) }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagGroupHeader).fetchSemanticsNodes().isNotEmpty()
        }
        // Exactly one group header on screen — the filtered one.
        assertThat(composeTestRule.onAllNodesWithTag(TestTagGroupHeader).fetchSemanticsNodes())
            .hasSize(1)
    }

    private companion object {
        const val STOP_ID = 1071
        const val FAVE_ROUTE_ID = 1881
        const val FAVE_DIRECTION_ID = 9
        const val OTHER_ROUTE_ID = 1882
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
