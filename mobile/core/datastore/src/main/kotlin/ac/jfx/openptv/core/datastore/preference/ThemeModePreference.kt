package ac.jfx.openptv.core.datastore.preference

import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope

/**
 * Light / Dark / System override for `OpenPtvTheme`. Default is `System`, so a fresh install
 * follows the OS-level dark-mode toggle until the user picks otherwise.
 *
 * The stored wire value is the lowercase case name (`"system"`, `"light"`, `"dark"`) — chosen
 * over enum ordinals so that reordering the sealed cases (or inserting a new one in the middle)
 * doesn't silently swap two users' settings.
 */
sealed class ThemeModePreference : Preference<ThemeModePreference.ThemeMode>() {
    /**
     * Three-way enum mirrored verbatim by `:core:designsystem`'s `ThemeMode`. Duplicated rather
     * than re-exported because `:core:datastore` deliberately does not depend on
     * `:core:designsystem` — the local can be read from any module without dragging in the
     * Material theme types.
     */
    enum class ThemeMode { System, Light, Dark }

    data object System : ThemeModePreference() {
        override val value: ThemeMode = ThemeMode.System
    }

    data object Light : ThemeModePreference() {
        override val value: ThemeMode = ThemeMode.Light
    }

    data object Dark : ThemeModePreference() {
        override val value: ThemeMode = ThemeMode.Dark
    }

    override fun put(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        persist(scope, dataStore, PreferenceKeys.THEME_MODE, value.name)
    }

    companion object {
        /** The fallback the composition local resolves to when no `SettingsProvider` is in scope. */
        val default: ThemeModePreference = System

        /**
         * Reconstitute a `ThemeModePreference` from the stored wire string. Unknown / null
         * values fall back to [default] so a forward-compat migration (e.g. a future
         * `Sepia` case) doesn't crash older builds reading the new value.
         */
        fun fromValue(stored: String?): ThemeModePreference =
            when (stored) {
                ThemeMode.System.name -> System
                ThemeMode.Light.name -> Light
                ThemeMode.Dark.name -> Dark
                else -> default
            }
    }
}

/**
 * Composition local for the active theme-mode preference. Compose code reads
 * `LocalThemeMode.current` rather than injecting a settings repo, matching the ReadYou pattern.
 * The fallback (`ThemeModePreference.default`) keeps previews and tests that do not install a
 * `SettingsProvider` rendering with sensible defaults.
 */
val LocalThemeMode = compositionLocalOf { ThemeModePreference.default }
