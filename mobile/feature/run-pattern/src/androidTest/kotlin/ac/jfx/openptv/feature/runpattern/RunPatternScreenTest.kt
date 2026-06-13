package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.data.test.FakeRunPatternRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.uitesthiltmanifest.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.inject.Inject

/**
 * Hilt-instrumented Compose UI tests for [RunPatternRoute]. Hosts the stateful route entry inside
 * [HiltComponentActivity] so `hiltViewModel()` resolves a real [RunPatternViewModel] backed by
 * the [FakeRunPatternRepository] swapped in by `FakeDataModule`'s `@TestInstallIn`. Same template
 * as `:feature:stop-detail`'s `StopDetailScreenTest`.
 */
@HiltAndroidTest
class RunPatternScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject
    lateinit var runPatternRepository: FakeRunPatternRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun loadingState_rendersSkeletonBeforeFirstEmission() {
        composeTestRule.setContent {
            RunPatternRoute(
                runRef = RunRef(RUN_REF),
                routeType = RouteType.Train,
            )
        }

        composeTestRule.onNodeWithTag(TestTagLoading).assertIsDisplayed()
        composeTestRule.onNodeWithText("Service").assertIsDisplayed()
    }

    @Test
    fun goldenPath_rendersTitleTimelineAndThisStopMarker() {
        composeTestRule.setContent {
            RunPatternRoute(
                runRef = RunRef(RUN_REF),
                routeType = RouteType.Train,
                // The Mother's third stop is Flinders Street, id 1071 — the tapped-through stop.
                fromStopId = StopId(1071),
            )
        }

        runBlocking {
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
        }

        composeTestRule.onNodeWithText("Lilydale to Flinders Street").assertIsDisplayed()
        composeTestRule.onNodeWithText("East Richmond Station").assertIsDisplayed()
        composeTestRule.onNodeWithText("Flinders Street Railway Station").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagThisStop).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagAsOf).assertIsDisplayed()
    }

    @Test
    fun title_collapsesWhenRouteAndDestinationShareAName() {
        composeTestRule.setContent {
            RunPatternRoute(
                runRef = RunRef(RUN_REF),
                routeType = RouteType.Train,
            )
        }

        runBlocking {
            // A run whose line and terminus share a name (the Lilydale line terminating at
            // Lilydale) would otherwise render "Lilydale to Lilydale".
            runPatternRepository.emitSuccess(
                RunPatternMother.aRunPattern().withDirectionName("Lilydale").build(),
            )
        }

        composeTestRule.onNodeWithText("Lilydale").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lilydale to Lilydale").assertDoesNotExist()
    }

    @Test
    fun errorState_rendersReasonAndRetryRecovers() {
        composeTestRule.setContent {
            RunPatternRoute(
                runRef = RunRef(RUN_REF),
                routeType = RouteType.Train,
            )
        }

        runBlocking {
            runPatternRepository.emitError(IOException("offline"))
        }

        composeTestRule.onNodeWithTag(TestTagError).assertIsDisplayed()

        // Retry re-subscribes; the fake's replay re-delivers... emit a success and confirm the
        // timeline replaces the error pane.
        runBlocking {
            runPatternRepository.emitSuccess(RunPatternMother.aRunPattern().build())
        }
        composeTestRule.onNodeWithTag(TestTagRetry).performClick()

        composeTestRule.onNodeWithText("East Richmond Station").assertIsDisplayed()
    }

    @Test
    fun emptyState_rendersEmptyPane() {
        composeTestRule.setContent {
            RunPatternRoute(
                runRef = RunRef(RUN_REF),
                routeType = RouteType.Train,
            )
        }

        runBlocking {
            runPatternRepository.emitSuccess(
                RunPatternMother.aRunPattern().withStops(emptyList()).build(),
            )
        }

        composeTestRule.onNodeWithTag(TestTagEmpty).assertIsDisplayed()
    }

    private companion object {
        private const val RUN_REF = "953527"
    }
}
