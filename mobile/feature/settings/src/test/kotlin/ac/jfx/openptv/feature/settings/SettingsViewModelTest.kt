package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.datastore.preference.TimeFormatPreference
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.testing.util.MainDispatcherRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [SettingsViewModel]. Mixed read/write surface:
 *
 * - **Theme mode + dynamic colour** writes are write-only — reads happen via the composition
 *   locals seeded by `SettingsProvider`, so we assert by observing the
 *   [UserPreferencesDataStore]'s typed flows directly and confirming each `setX` produces the
 *   matching emission.
 * - **Backend URL** is read off `SettingsRepository` (the existing post-onboarding seam),
 *   exposed via a `Flow<String>` and round-tripped via `setBackendBaseUrl`. We use the
 *   existing `FakeSettingsRepository` from `:core:data-test` rather than a real DataStore
 *   because the repository implementation is owned by `:app`, not this module — and the wire
 *   format is already covered by `SettingsRepositoryImpl`'s host-side tests.
 *
 * Uses a real Preferences DataStore on a temp file for the user-preferences side. Same shape
 * as `:core:datastore`'s `UserPreferencesDataStoreTest`. A hand-rolled fake would let the
 * tests pass even if the typed `put(scope, dataStore)` machinery silently broke against
 * an unexpected DataStore implementation — the wire format is part of the contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var prefsFile: File
    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var userPreferences: UserPreferencesDataStore
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        prefsFile = File(tempFolder.newFolder("datastore"), "openptv_user_prefs.preferences_pb")
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { prefsFile },
            )
        userPreferences = UserPreferencesDataStore(dataStore)
        settingsRepository = FakeSettingsRepository()
        viewModel = SettingsViewModel(userPreferences, settingsRepository)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `default themeMode is System before any write`() =
        runTest {
            userPreferences.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.default)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setThemeMode Light writes Light to datastore`() =
        runTest {
            userPreferences.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.default)
                viewModel.setThemeMode(ThemeModePreference.Light)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Light)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setThemeMode Dark writes Dark to datastore`() =
        runTest {
            userPreferences.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.default)
                viewModel.setThemeMode(ThemeModePreference.Dark)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Dark)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `successive theme writes re-emit through the typed flow`() =
        runTest {
            userPreferences.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.default)
                viewModel.setThemeMode(ThemeModePreference.Light)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Light)
                viewModel.setThemeMode(ThemeModePreference.Dark)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Dark)
                viewModel.setThemeMode(ThemeModePreference.System)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.System)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setDynamicColour Off writes Off to datastore`() =
        runTest {
            userPreferences.dynamicColour.test {
                // Default is platform-dependent — drain whatever the test JVM resolves it
                // to (Robolectric / host JVM: SDK_INT == 0, default == Off) and then assert
                // the explicit write lands.
                awaitItem()
                viewModel.setDynamicColour(DynamicColourPreference.Off)
                assertThat(awaitItem()).isEqualTo(DynamicColourPreference.Off)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setDynamicColour On writes On to datastore`() =
        runTest {
            userPreferences.dynamicColour.test {
                awaitItem()
                viewModel.setDynamicColour(DynamicColourPreference.On)
                assertThat(awaitItem()).isEqualTo(DynamicColourPreference.On)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------------
    // Time format — the surface added in #89.
    // ------------------------------------------------------------------------

    @Test
    fun `default timeFormat is System before any write`() =
        runTest {
            userPreferences.timeFormat.test {
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.default)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setTimeFormat TwelveHour writes TwelveHour to datastore`() =
        runTest {
            userPreferences.timeFormat.test {
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.default)
                viewModel.setTimeFormat(TimeFormatPreference.TwelveHour)
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.TwelveHour)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setTimeFormat TwentyFourHour writes TwentyFourHour to datastore`() =
        runTest {
            userPreferences.timeFormat.test {
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.default)
                viewModel.setTimeFormat(TimeFormatPreference.TwentyFourHour)
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.TwentyFourHour)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `successive timeFormat writes re-emit through the typed flow`() =
        runTest {
            userPreferences.timeFormat.test {
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.default)
                viewModel.setTimeFormat(TimeFormatPreference.TwelveHour)
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.TwelveHour)
                viewModel.setTimeFormat(TimeFormatPreference.TwentyFourHour)
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.TwentyFourHour)
                viewModel.setTimeFormat(TimeFormatPreference.System)
                assertThat(awaitItem()).isEqualTo(TimeFormatPreference.System)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------------
    // Server config — added in #81, extended in #102 / PR #113 feedback so the picker dialog
    // covers proxy + direct-PTV modes from a single seam.
    // ------------------------------------------------------------------------

    @Test
    fun `serverConfigState emits the seeded URL and direct-mode defaults`() =
        runTest {
            settingsRepository.seed(
                AppSettings(backendBaseUrl = "http://seeded.local/api/v3/", setupCompleted = true),
            )

            viewModel.serverConfigState.test {
                val state = awaitItem()
                assertThat(state.backendUrl).isEqualTo("http://seeded.local/api/v3/")
                assertThat(state.directMode).isFalse()
                assertThat(state.devId).isEmpty()
                assertThat(state.apiKey).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `defaultBackendUrl mirrors SettingsRepository defaultBackendBaseUrl`() {
        // The fake exposes a stable URL as both seed and default — pin the relationship so a
        // future change that decouples them gets a compile-time hint at every consumer.
        assertThat(viewModel.defaultBackendUrl).isEqualTo(settingsRepository.defaultBackendBaseUrl)
    }

    @Test
    fun `saveServerSelection Default persists default URL and clears direct mode`() =
        runTest {
            // Pre-seed direct mode on so the test asserts we flip it back off.
            settingsRepository.seed(
                AppSettings(
                    backendBaseUrl = "http://old.local/api/v3/",
                    setupCompleted = true,
                    directMode = true,
                    devId = "OLD",
                    apiKey = "OLDKEY",
                ),
            )

            viewModel.saveServerSelection(
                ServerPickerState(
                    defaultUrl = "http://default.local/api/v3/",
                    currentUrl = "http://old.local/api/v3/",
                    choice = ServerChoice.Default,
                ),
            )

            val after = settingsRepository.settings.first()
            assertThat(after.directMode).isFalse()
            assertThat(after.backendBaseUrl).isEqualTo("http://default.local/api/v3/")
        }

    @Test
    fun `saveServerSelection Custom persists typed URL and clears direct mode`() =
        runTest {
            viewModel.saveServerSelection(
                ServerPickerState(
                    defaultUrl = "http://default.local/api/v3/",
                    currentUrl = "http://default.local/api/v3/",
                    choice = ServerChoice.Custom,
                    customUrl = "http://custom.local/api/v3/",
                ),
            )

            val after = settingsRepository.settings.first()
            assertThat(after.directMode).isFalse()
            assertThat(after.backendBaseUrl).isEqualTo("http://custom.local/api/v3/")
        }

    @Test
    fun `saveServerSelection DirectPtv persists credentials and flips direct mode on`() =
        runTest {
            settingsRepository.seed(
                AppSettings(
                    backendBaseUrl = "http://proxy.local/api/v3/",
                    setupCompleted = true,
                ),
            )

            viewModel.saveServerSelection(
                ServerPickerState(
                    defaultUrl = "http://default.local/api/v3/",
                    currentUrl = "http://proxy.local/api/v3/",
                    choice = ServerChoice.DirectPtv,
                    devId = "3000176",
                    apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
                ),
            )

            val after = settingsRepository.settings.first()
            assertThat(after.directMode).isTrue()
            assertThat(after.devId).isEqualTo("3000176")
            assertThat(after.apiKey).isEqualTo("9c132d31-6a30-4cac-8d8b-8a1970834799")
            // Proxy URL stays as-is so a future flip-back doesn't lose the user's last value.
            assertThat(after.backendBaseUrl).isEqualTo("http://proxy.local/api/v3/")
        }

    @Test
    fun `serverConfigState reflects saveServerSelection writes through the same flow`() =
        runTest {
            viewModel.serverConfigState.test {
                // Drain initial empty state.
                awaitItem()
                viewModel.saveServerSelection(
                    ServerPickerState(
                        defaultUrl = "http://default.local/api/v3/",
                        currentUrl = "",
                        choice = ServerChoice.DirectPtv,
                        devId = "DEV",
                        apiKey = "KEY",
                    ),
                )
                val final = expectMostRecentItem()
                assertThat(final.directMode).isTrue()
                assertThat(final.devId).isEqualTo("DEV")
                assertThat(final.apiKey).isEqualTo("KEY")
                cancelAndIgnoreRemainingEvents()
            }
        }
}
