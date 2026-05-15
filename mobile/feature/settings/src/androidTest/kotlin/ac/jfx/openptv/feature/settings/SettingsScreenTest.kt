package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.datastore.SettingsProvider
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.datastore.preference.TimeFormatPreference
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.uitesthiltmanifest.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        // Reset to the typed default so cross-test state doesn't bleed in. We can't use
        // a fresh file per test without juggling Hilt component lifecycles, so an explicit
        // write is the simpler seam.
        seedThemeMode(ThemeModePreference.System)
        seedTimeFormat(TimeFormatPreference.System)
        // Reset the in-memory `FakeSettingsRepository` to a known state so a previous test's
        // server-URL write doesn't bleed into the next one. Cast is safe — the test graph
        // only ever binds the fake.
        (settingsRepository as FakeSettingsRepository).seed(
            AppSettings(
                backendBaseUrl = settingsRepository.defaultBackendBaseUrl,
                setupCompleted = true,
            ),
        )
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

    // ------------------------------------------------------------------------
    // Server picker — added in #81. Asserts that the row is reachable, the dialog renders
    // both choices, and a save persists through `SettingsRepository`. The picker dialog
    // itself doesn't add a new validation surface — `effectiveUrl.isNotBlank()` is covered
    // by `ServerPickerStateTest` on the JVM side, which is faster than driving Compose.
    // ------------------------------------------------------------------------

    @Test
    fun serverRow_showsCurrentBackendUrl() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        // The seeded URL is `defaultBackendBaseUrl` ("http://test.local/api/v3/" in the fake).
        composeTestRule.onNodeWithTag(TestTagServerRow).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(settingsRepository.defaultBackendBaseUrl)
            .assertIsDisplayed()
    }

    @Test
    fun tappingServerRow_opensDialogWithBothChoices() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagServerRow).performClick()

        composeTestRule.onNodeWithTag(TestTagServerDialog).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagServerDefaultChoice).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTagServerCustomChoice).assertIsDisplayed()
    }

    @Test
    fun savingCustomUrl_persistsThroughSettingsRepository() {
        val customUrl = "http://192.168.1.42:8080/api/v3/"

        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        // Open the dialog, switch to Custom, type the URL, save.
        composeTestRule.onNodeWithTag(TestTagServerRow).performClick()
        composeTestRule.onNodeWithTag(TestTagServerCustomChoice).performClick()
        composeTestRule.onNodeWithTag(TestTagServerCustomUrlField).performTextInput(customUrl)
        composeTestRule.onNodeWithTag(TestTagServerDialogSave).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            currentBackendUrl() == customUrl
        }
        assertThat(currentBackendUrl()).isEqualTo(customUrl)
    }

    // ------------------------------------------------------------------------
    // Time format — added in #89. Same pattern as the theme-mode rows above.
    // ------------------------------------------------------------------------

    @Test
    fun tappingTwentyFourHourRow_writesTwentyFourHourToDataStore() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagTimeFormatTwentyFour).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            currentTimeFormat() == TimeFormatPreference.TwentyFourHour
        }
        assertThat(currentTimeFormat()).isEqualTo(TimeFormatPreference.TwentyFourHour)
    }

    @Test
    fun tappingTwelveHourRow_writesTwelveHourToDataStore() {
        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagTimeFormatTwelve).performClick()

        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) {
            currentTimeFormat() == TimeFormatPreference.TwelveHour
        }
        assertThat(currentTimeFormat()).isEqualTo(TimeFormatPreference.TwelveHour)
    }

    @Test
    fun cancellingDialog_doesNotChangeBackendUrl() {
        val before = currentBackendUrl()

        composeTestRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                SettingsRoute(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTagServerRow).performClick()
        composeTestRule.onNodeWithTag(TestTagServerCustomChoice).performClick()
        composeTestRule.onNodeWithTag(TestTagServerCustomUrlField).performTextInput("http://elsewhere/")
        composeTestRule.onNodeWithTag(TestTagServerDialogCancel).performClick()

        // The dialog dismisses and the persisted URL is unchanged.
        assertThat(currentBackendUrl()).isEqualTo(before)
    }

    /** Blocking read off the typed flow — `first()` returns as soon as DataStore emits. */
    private fun currentThemeMode(): ThemeModePreference = runBlocking { userPreferences.themeMode.first() }

    private fun currentBackendUrl(): String =
        runBlocking { settingsRepository.settings.first().backendBaseUrl }

    private fun currentDynamicColour(): DynamicColourPreference = runBlocking { userPreferences.dynamicColour.first() }

    private fun currentTimeFormat(): TimeFormatPreference = runBlocking { userPreferences.timeFormat.first() }

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

    @OptIn(DelicateCoroutinesApi::class)
    private fun seedTimeFormat(target: TimeFormatPreference) {
        runBlocking {
            target.put(scope = GlobalScope, dataStore = userPreferences.dataStore)
            userPreferences.timeFormat.first { it == target }
        }
    }

    private companion object {
        // 5 s is plenty of headroom for the DataStore round-trip on a cold emulator.
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
