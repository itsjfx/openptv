package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.datastore.preference.LocalDynamicColour
import ac.jfx.openptv.core.datastore.preference.LocalFavouritesSort
import ac.jfx.openptv.core.datastore.preference.LocalThemeMode
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Composition-local propagation test for [SettingsProvider].
 *
 * The contract we're asserting: when `SettingsProvider` is wrapped around content, a child that
 * reads `LocalThemeMode.current` sees the value that's currently on disk — not just the
 * `default`. Without this guarantee `SettingsProvider` would be a no-op and the whole DSL
 * collapses into "every preference is always the default".
 *
 * Pinned to Robolectric SDK 34 (matches `:core:database`'s setup) and `manifest = NONE` because
 * the module ships no XML. Each test seeds an on-disk DataStore *before* composing, then asserts
 * the child node displays the seeded value.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class SettingsProviderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var prefsFile: File
    private lateinit var scope: CoroutineScope

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        prefsFile = File(tempFolder.newFolder("datastore"), "openptv_user_prefs.preferences_pb")
        scope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun openStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })

    @Test
    fun localThemeMode_propagates_collected_value_to_child() {
        val store = openStore()
        ThemeModePreference.Light.put(scope, store)
        // Block until DataStore has applied the write so the SettingsProvider's first
        // emission already reflects the seeded value (not the default → Light flicker).
        runBlocking { store.data.first() }
        val userPreferences = UserPreferencesDataStore(store)

        composeRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                Text(text = "theme=${LocalThemeMode.current.value.name}")
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText("theme=Light").assertExists()
            }.isSuccess
        }
        composeRule.onNodeWithText("theme=Light").assertIsDisplayed()
    }

    @Test
    fun localDynamicColour_propagates_collected_value_to_child() {
        val store = openStore()
        DynamicColourPreference.Off.put(scope, store)
        runBlocking { store.data.first() }
        val userPreferences = UserPreferencesDataStore(store)

        composeRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                Text(text = "dynamic=${LocalDynamicColour.current.value}")
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText("dynamic=false").assertExists()
            }.isSuccess
        }
        composeRule.onNodeWithText("dynamic=false").assertIsDisplayed()
    }

    @Test
    fun localFavouritesSort_propagates_collected_value_to_child() {
        val store = openStore()
        FavouritesSortPreference.Alphabetical.put(scope, store)
        runBlocking { store.data.first() }
        val userPreferences = UserPreferencesDataStore(store)

        composeRule.setContent {
            SettingsProvider(userPreferences = userPreferences) {
                Text(text = "sort=${LocalFavouritesSort.current.value.name}")
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText("sort=Alphabetical").assertExists()
            }.isSuccess
        }
        composeRule.onNodeWithText("sort=Alphabetical").assertIsDisplayed()
    }

    @Test
    fun without_settings_provider_locals_resolve_to_default() {
        composeRule.setContent {
            val theme = LocalThemeMode.current
            val colour = LocalDynamicColour.current
            val sort = LocalFavouritesSort.current
            Text(
                text =
                    "t=${theme.value.name}" +
                        " d=${colour.value}" +
                        " s=${sort.value.name}",
            )
        }

        val expected =
            "t=${ThemeModePreference.default.value.name}" +
                " d=${DynamicColourPreference.default.value}" +
                " s=${FavouritesSortPreference.default.value.name}"
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
