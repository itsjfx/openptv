package ac.jfx.openptv.core.datastore.preference

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Single registry of every [androidx.datastore.preferences.core.Preferences.Key] in this module.
 *
 * Keeping them in one file (rather than spreading them across each preference class) means:
 *  - the on-disk surface is auditable at a glance — useful when planning a migration,
 *  - a typo in one of the literal key names shows up next to its neighbours,
 *  - `SettingsProvider` and `UserPreferencesDataStore` import the same key constants so a
 *    rename is a single Kotlin refactor.
 *
 * **Do not rename a key once it ships.** The wire format is the public API of the on-disk
 * preferences file; renaming a key silently loses the user's saved value at next launch. If a
 * key truly needs to change, write a DataStore migration that reads the old key on first
 * access and writes the new one.
 */
internal object PreferenceKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOUR = stringPreferencesKey("dynamic_colour")
    val FAVOURITES_SORT = stringPreferencesKey("favourites_sort")
    val TIME_FORMAT = stringPreferencesKey("time_format")

    /**
     * Nearby map's route-type chip selection (issue #112). Values are stringified
     * `RouteType.toCode()` ints — the PTV wire format. Persisted as a `Set<String>` so the
     * "trams only" chip configuration survives an app restart.
     */
    val MAP_ROUTE_TYPE_FILTER = stringSetPreferencesKey("map_route_type_filter")
}
