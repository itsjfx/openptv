package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Write-only ViewModel for the Appearance settings.
 *
 * The screen reads the current theme-mode and dynamic-colour values from the composition
 * locals (`LocalThemeMode` / `LocalDynamicColour`) seeded by [SettingsProvider] at the app
 * root, so the UI updates immediately when DataStore re-emits. Keeping the ViewModel
 * write-only is deliberate — duplicating those flows here would mean two sources of truth and
 * a one-frame flicker between the local's value and the ViewModel's `StateFlow`. The DSL is
 * the SSOT; the screen pipes events back through this ViewModel only because
 * `Preference.put(scope, dataStore)` needs a `CoroutineScope` that outlives the composition
 * (so a quick back-press during a write doesn't drop the persist).
 *
 * `setThemeMode` / `setDynamicColour` accept the full typed preference (not an enum value)
 * because the DSL's exhaustive `when` matching belongs at the call site — the screen passes
 * `ThemeModePreference.Light` rather than a bare `ThemeMode.Light` enum, so adding a new
 * case (e.g. a future `Sepia`) gets the compile-time hint at every consumer of the DSL.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val userPreferences: UserPreferencesDataStore,
    ) : ViewModel() {
        fun setThemeMode(themeMode: ThemeModePreference) {
            themeMode.put(viewModelScope, userPreferences.dataStore)
        }

        fun setDynamicColour(dynamicColour: DynamicColourPreference) {
            dynamicColour.put(viewModelScope, userPreferences.dataStore)
        }
    }
