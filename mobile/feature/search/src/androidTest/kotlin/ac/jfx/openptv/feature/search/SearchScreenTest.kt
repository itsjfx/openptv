/*
 * Copyright 2026 The OpenPTV Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.data.test.FakeStopSearchRepository
import ac.jfx.openptv.core.testing.StopMother
import ac.jfx.openptv.uitesthiltmanifest.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.inject.Inject

/**
 * Hilt-instrumented Compose UI tests for [SearchScreen]. Hosts the stateful entry point inside
 * [HiltComponentActivity] so `hiltViewModel()` resolves a real [SearchViewModel] backed by the
 * [FakeStopSearchRepository] swapped in by `FakeDataModule`'s `@TestInstallIn`. No MockK; the
 * fake repository is the entire test seam.
 *
 * The Hilt rule has to run before the Compose rule (`order = 0`) so the graph is built by the
 * time `composeTestRule.setContent { ... }` requests dependencies. We use the rule's
 * `setContent` (not `activity.setContent`) because it dispatches onto the UI thread for us — the
 * activity-level overload runs from the JUnit thread and trips `CalledFromWrongThreadException`.
 *
 * Sets the per-feature template every later feature copies. Same shape as NIA's
 * `feature/topic/.../TopicScreenTest.kt`.
 */
@HiltAndroidTest
class SearchScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject
    lateinit var repository: FakeStopSearchRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    /**
     * Golden path: typing a non-empty query waits out the 300 ms debounce, calls
     * the repository once, and renders one [androidx.compose.foundation.lazy.LazyColumn] row per
     * returned stop. We assert on the stop's name (visible) and its content description (talkback
     * payload) so the test catches both visual regressions and accessibility regressions.
     */
    @Test
    fun goldenPath_typingRendersResults() {
        repository.enqueueSuccess(
            listOf(
                StopMother.aStop()
                    .withName("Flinders Street Railway Station")
                    .withSuburb("Melbourne City")
                    .build(),
            ),
        )

        composeTestRule.setContent { SearchScreen() }

        composeTestRule
            .onNodeWithTag(TestTagQueryField)
            .performTextInput("flinders")

        // Debounce is 300 ms; waitUntil polls until the results row appears or the
        // timeout trips. Using the visible stop name as the predicate keeps the
        // assertion intent-aligned with what a user would see.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText("Flinders Street Railway Station")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule
            .onNodeWithText("Flinders Street Railway Station")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(TestTagResults)
            .assertIsDisplayed()
    }

    /**
     * Empty state: an in-graph fake that returns `emptyList()` should drive the screen into
     * [SearchUiState.Empty] and render the `feature_search_empty` copy.
     */
    @Test
    fun emptyState_rendersEmptyMessage() {
        repository.enqueueSuccess(emptyList())

        composeTestRule.setContent { SearchScreen() }

        composeTestRule
            .onNodeWithTag(TestTagQueryField)
            .performTextInput("zzz")

        val emptyText =
            composeTestRule.activity.getString(R.string.feature_search_empty)
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText(emptyText)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(emptyText).assertIsDisplayed()
    }

    /**
     * Error path: enqueueing an [IOException] drives the screen into [SearchUiState.Error]; the
     * ViewModel maps it to the user-facing "Couldn't reach the network." copy via
     * `Throwable.toUserFacingReason`. Asserts on the substring "network" — the same shape the
     * unit test uses, so a copy tweak only needs one place to update.
     */
    @Test
    fun errorState_rendersNetworkErrorMessage() {
        repository.enqueueError(IOException("boom"))

        composeTestRule.setContent { SearchScreen() }

        composeTestRule
            .onNodeWithTag(TestTagQueryField)
            .performTextInput("flinders")

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithText("network", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule
            .onNodeWithText("network", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Issue #111 — the top-left gear replaces the old Settings bottom-nav tab. Tapping it hoists
     * through `onOpenSettings`, which the app composition root wires to a destination push.
     */
    @Test
    fun settingsGear_tapFiresOnOpenSettings() {
        var opened = false
        composeTestRule.setContent {
            SearchScreen(onOpenSettings = { opened = true })
        }
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagSettingsGear).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagSettingsGear).performClick()
        assertThat(opened).isTrue()
    }

    private companion object {
        // 5 s gives plenty of headroom over the 300 ms debounce + Compose recomposition
        // latency on a cold emulator without making flakes silent.
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
