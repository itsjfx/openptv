package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.datastore.SettingsProvider
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.uitesthiltmanifest.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Hilt-instrumented Compose UI test for [SettingsRoute]. Hosts the route inside
 * [HiltComponentActivity] so `hiltViewModel()` resolves a real [SettingsViewModel] backed
 * by the [FakeUserPreferencesModule]'s temp-file DataStore. Wraps the route in
 * [SettingsProvider] so the composition locals (`LocalThemeMode`, `LocalDynamicColour`)
 * emit from the same DataStore the ViewModel writes to — which is what closes the loop:
 * tap the row, the typed `put(...)` writes through DataStore, the local re-emits, the row
 * re-renders with the new selection. Same shape as `:feature:search`'s `SearchScreenTest`.
 *
 * Each test seeds the persisted defaults in `@Before` so a previous test's writes don't
 * leak into this one. The DataStore file is namespaced as `openptv_user_prefs_test` inside
 * the test app's files dir.
 *
 * Assertions read the DataStore back through `runBlocking { themeMode.first() }` rather
 * than wiring up `runTest` — `waitUntil`'s lambda isn't a coroutine context, and the
 * round-trip is fast enough that a blocking read on the test thread is the simpler seam.
 */
@HiltAndroidTest
class SettingsScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject
    lateinit var userPreferences: UserPreferencesDataStore

    @Before
    fun setUp() {
        hiltRule.inject()
        // Reset to the typed default so cross-test state doesn't bleed in. We can't use
        // a fresh file per test without juggling Hilt component lifecycles, so an explicit
        // write is the simpler seam.
        seedThemeMode(ThemeModePreference.System)
    }

    @Test
    fun tappingLightRow_writesLightToDataStore() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagThemeLight).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            currentThemeMode() == ThemeModePreference.Light
        }
        assertThat(currentThemeMode()).isEqualTo(ThemeModePreference.Light)
    }

    @Test
    fun tappingDarkRow_writesDarkToDataStore() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagThemeDark).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            currentThemeMode() == ThemeModePreference.Dark
        }
        assertThat(currentThemeMode()).isEqualTo(ThemeModePreference.Dark)
    }

    @Test
    fun tappingSystemRow_writesSystemToDataStore() {
        // Pre-seed Light so the System tap is a real change.
        seedThemeMode(ThemeModePreference.Light)

        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagThemeSystem).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            currentThemeMode() == ThemeModePreference.System
        }
        assertThat(currentThemeMode()).isEqualTo(ThemeModePreference.System)
    }

    @Test
    fun appearanceSectionHeaderIsDisplayed() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.feature_settings_appearance_section),
            )
            .assertIsDisplayed()
    }

    @Test
    fun togglingDynamicColour_writesToDataStore_onSupportedPlatforms() {
        // The androidTest target runs on a real Android device / emulator, so the toggle
        // is enabled iff `Build.VERSION.SDK_INT >= S`. Branch the assertion on the
        // platform instead of pinning a per-API test — the cheaper expectation is that
        // the row's visible affordance matches the real platform behaviour.
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val initial = currentDynamicColour()
            composeTestRule.onNodeWithTag(TestTagDynamicColourRow).performClick()

            val expectedAfterClick =
                if (initial == DynamicColourPreference.On) {
                    DynamicColourPreference.Off
                } else {
                    DynamicColourPreference.On
                }
            composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
                currentDynamicColour() == expectedAfterClick
            }
            assertThat(currentDynamicColour()).isEqualTo(expectedAfterClick)
        } else {
            // Pre-12 — the disabled-subtitle copy is the user-visible explanation.
            composeTestRule
                .onNodeWithText(
                    composeTestRule.activity.getString(
                        R.string.feature_settings_dynamic_colour_unsupported_subtitle,
                    ),
                )
                .assertIsDisplayed()
        }
    }

    /** Blocking read off the typed flow — `first()` returns as soon as DataStore emits. */
    private fun currentThemeMode(): ThemeModePreference = runBlocking { userPreferences.themeMode.first() }

    private fun currentDynamicColour(): DynamicColourPreference = runBlocking { userPreferences.dynamicColour.first() }

    @OptIn(DelicateCoroutinesApi::class)
    private fun seedThemeMode(target: ThemeModePreference) {
        // GlobalScope is the intentional seam here — the `put(scope, ...)` write needs to
        // outlive the test method, and adding a per-test scope plumbed through Hilt would
        // be more ceremony than the test deserves.
        runBlocking {
            target.put(scope = GlobalScope, dataStore = userPreferences.dataStore)
            userPreferences.themeMode.first { it == target }
        }
    }

    private companion object {
        // 5 s is plenty of headroom for the DataStore round-trip on a cold emulator.
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
