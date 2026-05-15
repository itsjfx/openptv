package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteRouteAtStopMother
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
import kotlinx.datetime.Clock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * Hilt-instrumented Compose UI test for [FavouritesRoute]. Mirrors `:feature:stop-detail`'s
 * `StopDetailScreenTest`: real ViewModel resolved through Hilt, fakes from `:core:data-test`
 * swapped in via `FakeDataModule`'s `@TestInstallIn`. No MockK.
 */
@HiltAndroidTest
class FavouritesScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject
    lateinit var favouritesRepository: FakeFavouritesRepository

    @Inject
    lateinit var departureRepository: FakeDepartureRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun emptyState_showsCopyAndSearchCta() {
        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { _, _, _, _ -> },
                onOpenSearch = { },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithTag(TestTagEmpty)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagEmpty).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(R.string.feature_favourites_empty),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagEmptyCta).assertIsDisplayed()
    }

    @Test
    fun threeRows_renderAllAndTapNavigatesWithFocusArgs() {
        val now = Clock.System.now()
        favouritesRepository.seed(
            listOf(
                FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                    .withStopId(1).withRouteId(11).withDirectionId(111)
                    .withStopName("Brunswick").withRouteNumber("19")
                    .withPosition(0)
                    .build(),
                FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                    .withStopId(2).withRouteId(22).withDirectionId(222)
                    .withStopName("Carlton").withRouteNumber("57")
                    .withPosition(1)
                    .build(),
                FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                    .withStopId(3).withRouteId(33).withDirectionId(333)
                    .withStopName("Footscray").withRouteNumber("82")
                    .withPosition(2)
                    .build(),
            ),
        )
        // One match per favourite for the next-departure tick.
        repeat(3) {
            departureRepository.enqueueSuccess(
                listOf(
                    DepartureMother.aDeparture()
                        .withRouteId(11 * (it + 1)).withDirectionId(111 * (it + 1))
                        .withScheduledDepartureUtc(now + 5.minutes)
                        .withEstimatedDepartureUtc(now + 5.minutes)
                        .build(),
                ),
            )
        }
        var captured: List<Int> = emptyList()
        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { stopId, routeTypeCode, focusRouteId, focusDirectionId ->
                    captured = listOf(stopId, routeTypeCode, focusRouteId, focusDirectionId)
                },
                onOpenSearch = { /* no-op */ },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagRowList).fetchSemanticsNodes().isNotEmpty()
        }
        // Each seeded row is present, addressed by its composite-key test tag.
        listOf(
            FavouriteKey(1, 11, 111),
            FavouriteKey(2, 22, 222),
            FavouriteKey(3, 33, 333),
        ).forEach { key ->
            composeTestRule.onNodeWithTag(testTagForRow(key)).assertIsDisplayed()
        }
        // Tap the Carlton row.
        composeTestRule.onNodeWithTag(testTagForRow(FavouriteKey(2, 22, 222))).performClick()
        // Nav callback fired with the right focus args.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            captured.isNotEmpty()
        }
        assertThat(captured[0]).isEqualTo(2)
        assertThat(captured[2]).isEqualTo(22)
        assertThat(captured[3]).isEqualTo(222)
    }

    @Test
    fun deleteButton_onlyVisibleInEditMode() {
        favouritesRepository.seed(
            listOf(
                FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                    .withStopId(1).withRouteId(11).withDirectionId(111)
                    .withStopName("Brunswick").withPosition(0)
                    .build(),
            ),
        )
        departureRepository.enqueueSuccess(emptyList())

        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { _, _, _, _ -> },
                onOpenSearch = { },
            )
        }

        val deleteTag = testTagForDelete(FavouriteKey(1, 11, 111))
        // Wait for the row to render.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(testTagForRow(FavouriteKey(1, 11, 111)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Delete is hidden until edit mode toggles on.
        composeTestRule.onAllNodesWithTag(deleteTag).fetchSemanticsNodes().let { nodes ->
            assertThat(nodes).isEmpty()
        }
        // Toggle edit mode on.
        composeTestRule.onNodeWithTag(TestTagEditToggle).performClick()
        // Now the delete button is visible.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(deleteTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(deleteTag).assertIsDisplayed()
    }

    @Test
    fun tappingDeleteShowsSnackbarAndUndoRestoresFavourite() {
        favouritesRepository.seed(
            listOf(
                FavouriteRouteAtStopMother.aFavouriteRouteAtStop()
                    .withStopId(1).withRouteId(11).withDirectionId(111)
                    .withStopName("Brunswick").withPosition(0)
                    .build(),
            ),
        )
        departureRepository.enqueueSuccess(emptyList())

        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { _, _, _, _ -> },
                onOpenSearch = { },
            )
        }

        val deleteTag = testTagForDelete(FavouriteKey(1, 11, 111))
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(testTagForRow(FavouriteKey(1, 11, 111)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Enter edit mode so the delete affordance becomes available.
        composeTestRule.onNodeWithTag(TestTagEditToggle).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(deleteTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(deleteTag).performClick()

        // Repository reflects the removal.
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            favouritesRepository.current.isEmpty()
        }
        assertThat(favouritesRepository.current).isEmpty()

        // Snackbar with undo action appears — tap it.
        val undoLabel = composeTestRule.activity.getString(R.string.feature_favourites_undo)
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(undoLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(undoLabel).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            favouritesRepository.current.isNotEmpty()
        }
        assertThat(favouritesRepository.current).hasSize(1)
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
