package ac.jfx.openptv.core.datastore.preference

import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope

/**
 * Sort order for the favourites list. Default is `Manual` so the user-controlled drag order
 * (set up by the favourites screen in #34) survives a sort-mode round trip — switching to
 * `Alphabetical`, then back to `Manual`, restores the previous manual order.
 *
 * `Nearest` is shipped on the DSL surface but is disabled in the UI until Phase 5 lands the
 * location stack. Keeping the case here means the future toggle is a one-line UI change rather
 * than a wire-format migration.
 */
sealed class FavouritesSortPreference : Preference<FavouritesSortPreference.SortMode>() {
    enum class SortMode { Manual, Alphabetical, Nearest }

    data object Manual : FavouritesSortPreference() {
        override val value: SortMode = SortMode.Manual
    }

    data object Alphabetical : FavouritesSortPreference() {
        override val value: SortMode = SortMode.Alphabetical
    }

    data object Nearest : FavouritesSortPreference() {
        override val value: SortMode = SortMode.Nearest
    }

    override fun put(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        persist(scope, dataStore, PreferenceKeys.FAVOURITES_SORT, value.name)
    }

    companion object {
        val default: FavouritesSortPreference = Manual

        fun fromValue(stored: String?): FavouritesSortPreference =
            when (stored) {
                SortMode.Manual.name -> Manual
                SortMode.Alphabetical.name -> Alphabetical
                SortMode.Nearest.name -> Nearest
                else -> default
            }
    }
}

/** Composition local for favourites sort mode. */
val LocalFavouritesSort = compositionLocalOf { FavouritesSortPreference.default }
