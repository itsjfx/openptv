package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.data.test.FakeDepartureRepository
import ac.jfx.openptv.core.data.test.FakeFavouritesRepository
import ac.jfx.openptv.core.testing.DepartureMother
import ac.jfx.openptv.core.testing.FavouriteDestinationAtStopMother
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
 * Hilt-instrumented Compose UI test for [FavouritesRoute]. Real ViewModel resolved through Hilt,
 * fakes from `:core:data-test` swapped in via `FakeDataModule`'s `@TestInstallIn`. No MockK.
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
                onOpenStopDetail = { _, _, _ -> },
                onOpenSearch = { },
                onOpenSettings = { },
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
    fun threeRows_renderAllAndTapNavigatesWithFocusDestinationKey() {
        val now = Clock.System.now()
        favouritesRepository.seed(
            listOf(
                FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                    .withStopId(1).withDestinationKey("north coburg").withDestinationName("North Coburg")
                    .withStopName("Brunswick").withPosition(0).build(),
                FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                    .withStopId(2).withDestinationKey("city").withDestinationName("City")
                    .withStopName("Carlton").withPosition(1).build(),
                FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                    .withStopId(3).withDestinationKey("footscray").withDestinationName("Footscray")
                    .withStopName("Footscray Station").withPosition(2).build(),
            ),
        )
        // One match per favourite for the next-departure tick.
        repeat(3) { i ->
            val dest = listOf("North Coburg", "City", "Footscray")[i]
            departureRepository.enqueueSuccess(
                listOf(
                    DepartureMother.aDeparture()
                        .withDirectionName(dest)
                        .withScheduledDepartureUtc(now + 5.minutes)
                        .withEstimatedDepartureUtc(now + 5.minutes)
                        .build(),
                ),
            )
        }
        var captured: Triple<Int, Int, String?>? = null
        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { stopId, routeTypeCode, focusDestinationKey ->
                    captured = Triple(stopId, routeTypeCode, focusDestinationKey)
                },
                onOpenSearch = { /* no-op */ },
                onOpenSettings = { /* no-op */ },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagRowList).fetchSemanticsNodes().isNotEmpty()
        }
        listOf(
            FavouriteKey(1, "north coburg"),
            FavouriteKey(2, "city"),
            FavouriteKey(3, "footscray"),
        ).forEach { key ->
            composeTestRule.onNodeWithTag(testTagForRow(key)).assertIsDisplayed()
        }
        // Tap the Carlton → City row.
        composeTestRule.onNodeWithTag(testTagForRow(FavouriteKey(2, "city"))).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            captured != null
        }
        val (stopId, _, focus) = captured!!
        assertThat(stopId).isEqualTo(2)
        assertThat(focus).isEqualTo("city")
    }

    @Test
    fun deleteButton_onlyVisibleInEditMode() {
        favouritesRepository.seed(
            listOf(
                FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                    .withStopId(1).withDestinationKey("north coburg")
                    .withStopName("Brunswick").withPosition(0).build(),
            ),
        )
        departureRepository.enqueueSuccess(emptyList())

        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { _, _, _ -> },
                onOpenSearch = { },
                onOpenSettings = { },
            )
        }

        val rowKey = FavouriteKey(1, "north coburg")
        val deleteTag = testTagForDelete(rowKey)
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(testTagForRow(rowKey)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithTag(deleteTag).fetchSemanticsNodes().let { nodes ->
            assertThat(nodes).isEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagEditToggle).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(deleteTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(deleteTag).assertIsDisplayed()
    }

    @Test
    fun tappingDeleteShowsSnackbarAndUndoRestoresFavourite() {
        favouritesRepository.seed(
            listOf(
                FavouriteDestinationAtStopMother.aFavouriteDestinationAtStop()
                    .withStopId(1).withDestinationKey("north coburg")
                    .withStopName("Brunswick").withPosition(0).build(),
            ),
        )
        departureRepository.enqueueSuccess(emptyList())

        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { _, _, _ -> },
                onOpenSearch = { },
                onOpenSettings = { },
            )
        }

        val rowKey = FavouriteKey(1, "north coburg")
        val deleteTag = testTagForDelete(rowKey)
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(testTagForRow(rowKey)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagEditToggle).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(deleteTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(deleteTag).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            favouritesRepository.current.isEmpty()
        }
        assertThat(favouritesRepository.current).isEmpty()

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

    @Test
    fun settingsGear_tapFiresOnOpenSettings() {
        var opened = false
        composeTestRule.setContent {
            FavouritesRoute(
                onOpenStopDetail = { _, _, _ -> },
                onOpenSearch = { },
                onOpenSettings = { opened = true },
            )
        }
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(TestTagSettingsGear).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(TestTagSettingsGear).performClick()
        assertThat(opened).isTrue()
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
