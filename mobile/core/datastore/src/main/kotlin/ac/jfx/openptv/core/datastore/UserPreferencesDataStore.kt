package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.datastore.preference.MapRouteTypeFilterPreference
import ac.jfx.openptv.core.datastore.preference.PreferenceKeys
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.datastore.preference.TimeFormatPreference
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Singleton facade over the user-preferences [DataStore]. Two audiences:
 *
 *  1. **Non-Compose consumers** — workers, ViewModels that need to write a preference in
 *     response to a non-UI event, the future settings ViewModel. These get typed `Flow`
 *     accessors (`themeMode`, `dynamicColour`, `favouritesSort`) that already know how to
 *     decode the stored wire string into a `Preference<T>` subclass.
 *  2. **`SettingsProvider`** — collects each flow exactly once at the root of the composition
 *     and pushes the values down through composition locals.
 *
 * The class deliberately holds a reference to the [DataStore] (not the wrapped `Preferences`)
 * so subclasses of `Preference<T>` can call `put(scope, dataStore)` directly without a second
 * Hilt-bound parameter at the call site. Exposing it as a read-only property keeps the
 * "preferences are written through the typed DSL, not via raw key edits" invariant — there is
 * no public mutating method on this class.
 *
 * Production code resolves this class through Hilt ([UserPreferencesDataStoreModule] provides
 * a `@Singleton` instance backed by the `@UserPreferences` qualified `DataStore`). The
 * constructor is `public` so consumer-module tests (e.g. `:feature:settings`'s
 * `SettingsViewModelTest`) can build a temp-file-backed real DataStore and wrap it directly
 * without taking a Hilt dependency. The class is otherwise stateless beyond the underlying
 * store, so a hand-rolled fake would just re-implement the typed flows around an in-memory
 * map and earn nothing — using the real wrapper against a real DataStore catches more wire-
 * format issues at unit-test speed.
 */
class UserPreferencesDataStore(
    val dataStore: DataStore<Preferences>,
) {
    /** Current [ThemeModePreference], re-emitted on every write. */
    val themeMode: Flow<ThemeModePreference> =
        dataStore.data.map { prefs ->
            ThemeModePreference.fromValue(prefs[PreferenceKeys.THEME_MODE])
        }

    /** Current [DynamicColourPreference], re-emitted on every write. */
    val dynamicColour: Flow<DynamicColourPreference> =
        dataStore.data.map { prefs ->
            DynamicColourPreference.fromValue(prefs[PreferenceKeys.DYNAMIC_COLOUR])
        }

    /** Current [FavouritesSortPreference], re-emitted on every write. */
    val favouritesSort: Flow<FavouritesSortPreference> =
        dataStore.data.map { prefs ->
            FavouritesSortPreference.fromValue(prefs[PreferenceKeys.FAVOURITES_SORT])
        }

    /** Current [TimeFormatPreference], re-emitted on every write. */
    val timeFormat: Flow<TimeFormatPreference> =
        dataStore.data.map { prefs ->
            TimeFormatPreference.fromValue(prefs[PreferenceKeys.TIME_FORMAT])
        }

    /**
     * Current [MapRouteTypeFilterPreference] for the Nearby map (issue #112) — the set of
     * chip-selected `RouteType`s persisted across app launches. Re-emitted on every write.
     */
    val mapRouteTypeFilter: Flow<MapRouteTypeFilterPreference> =
        dataStore.data.map { prefs ->
            MapRouteTypeFilterPreference.fromValue(prefs[PreferenceKeys.MAP_ROUTE_TYPE_FILTER])
        }
}
