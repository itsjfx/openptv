package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.datastore.preference.LocalDynamicColour
import ac.jfx.openptv.core.datastore.preference.LocalFavouritesSort
import ac.jfx.openptv.core.datastore.preference.LocalThemeMode
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Composable root provider that collects every user preference from DataStore and pushes the
 * values down the composition through their respective composition locals.
 *
 * Wrap app content **once** at the root (`MainActivity.setContent { ... }`), not per-screen.
 * Children read `LocalThemeMode.current` etc. without injecting a repository. ReadYou's
 * `AccountSettings.kt:21-37` is the pattern this mirrors.
 *
 * **First-frame behaviour**: `collectAsStateWithLifecycle` seeds the state with the typed
 * `default` for each preference, so the first composition does not render with `null`-shaped
 * data. DataStore then emits the persisted value (or sticks with the default if nothing's been
 * written yet) and the composition recomposes with the user's choice.
 *
 * **Recipe to add a new setting** (also documented in the module README):
 *  1. Create a new sealed `Preference<T>` subclass with `data object` cases, a `Preferences.Key`
 *     entry in [ac.jfx.openptv.core.datastore.preference.PreferenceKeys], a companion `default`
 *     and a `fromValue(stored: String?)` parser.
 *  2. Add a `compositionLocalOf { default }` in the same file.
 *  3. Add **one** line to [SettingsProvider] that wires the new local to a typed flow on
 *     [UserPreferencesDataStore].
 */
@Composable
fun SettingsProvider(
    userPreferences: UserPreferencesDataStore,
    content: @Composable () -> Unit,
) {
    val themeMode by userPreferences.themeMode.collectAsStateWithLifecycle(
        initialValue = remember { ThemeModePreference.default },
    )
    val dynamicColour by userPreferences.dynamicColour.collectAsStateWithLifecycle(
        initialValue = remember { DynamicColourPreference.default },
    )
    val favouritesSort by userPreferences.favouritesSort.collectAsStateWithLifecycle(
        initialValue = remember { FavouritesSortPreference.default },
    )

    CompositionLocalProvider(
        LocalThemeMode provides themeMode,
        LocalDynamicColour provides dynamicColour,
        LocalFavouritesSort provides favouritesSort,
        content = content,
    )
}
