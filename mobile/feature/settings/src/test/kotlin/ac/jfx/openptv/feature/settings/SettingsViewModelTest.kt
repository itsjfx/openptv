package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [SettingsViewModel]. The ViewModel is write-only — reads happen via the
 * composition locals seeded by `SettingsProvider`, so there's no `StateFlow<UiState>` to
 * assert against. We assert by observing the [UserPreferencesDataStore]'s typed flows
 * directly and confirming each `setX` produces the matching emission.
 *
 * Uses a real Preferences DataStore on a temp file rather than a fake. Same shape as
 * `:core:datastore`'s `UserPreferencesDataStoreTest`. A hand-rolled fake would let the
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
        viewModel = SettingsViewModel(userPreferences)
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
}
