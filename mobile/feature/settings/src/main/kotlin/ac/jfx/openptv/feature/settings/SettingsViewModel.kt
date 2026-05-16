package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.datastore.preference.TimeFormatPreference
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * **Theme mode + dynamic colour** are read by the screen via the composition locals
 * (`LocalThemeMode` / `LocalDynamicColour`) seeded by `SettingsProvider` at the app root, so the
 * UI updates immediately when DataStore re-emits. Keeping those write-only is deliberate —
 * duplicating those flows here would mean two sources of truth and a one-frame flicker between
 * the local's value and the ViewModel's `StateFlow`. The DSL is the SSOT; the screen pipes
 * events back through this ViewModel only because `Preference.put(scope, dataStore)` needs a
 * `CoroutineScope` that outlives the composition (so a quick back-press during a write doesn't
 * drop the persist).
 *
 * **Server URL + direct-mode credentials** are read here because `SettingsRepository` predates
 * the typed-DSL machinery — it owns its own `Flow<AppSettings>`, not a composition local.
 * Exposing [directModeState] as a `StateFlow` lets the direct-mode UI render the persisted
 * toggle / devid / api-key without the screen having to inject the repository directly. The API
 * key intentionally does NOT flow through a composition local: only this ViewModel reads it.
 * Writes go through [setDirectMode] / [setDevId] / [setApiKey], which delegate to the
 * repository.
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
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        /**
         * Currently-active backend base URL. Backed by `SettingsRepository.settings` so a write
         * from the picker dialog (or anywhere else) re-emits here without an explicit refresh.
         * Initial value is empty — the row's subtitle handles that as a transient one-frame
         * "loading" state and recomposes the moment the repository emits its first value.
         */
        val currentBackendUrl: Flow<String> =
            settingsRepository.settings
                .map { it.backendBaseUrl }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT_MILLIS),
                    initialValue = "",
                )

        /**
         * Direct-mode toggle + credentials, mapped off the same repository flow. One state object
         * (rather than three separate flows) keeps the toggle and the field values in lock-step
         * so the screen never observes a half-applied write. `apiKey` rides this flow only so
         * the masked field can re-render after a write — composition locals deliberately don't
         * carry it (one fewer place the secret could leak).
         */
        val directModeState: Flow<DirectModeState> =
            settingsRepository.settings
                .map {
                    DirectModeState(
                        enabled = it.directMode,
                        devId = it.devId,
                        apiKey = it.apiKey,
                    )
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT_MILLIS),
                    initialValue = DirectModeState.empty,
                )

        /**
         * Bundled default backend URL — the value the picker dialog labels as "Default" and
         * the value the dialog opens on when the user has never picked a custom URL. Read off
         * the repository so the feature module doesn't need to know about `:app`'s
         * `BuildConfig`.
         */
        val defaultBackendUrl: String = settingsRepository.defaultBackendBaseUrl

        fun setThemeMode(themeMode: ThemeModePreference) {
            themeMode.put(viewModelScope, userPreferences.dataStore)
        }

        fun setDynamicColour(dynamicColour: DynamicColourPreference) {
            dynamicColour.put(viewModelScope, userPreferences.dataStore)
        }

        /**
         * Persist the user's 12 / 24-hour clock preference. Write-only here for the same reason
         * `setThemeMode` is — the screen reads the current value through `LocalTimeFormat.current`
         * (seeded by `SettingsProvider`), so duplicating the flow on this ViewModel would create
         * a one-frame flicker between the local's value and the StateFlow's emission.
         */
        fun setTimeFormat(timeFormat: TimeFormatPreference) {
            timeFormat.put(viewModelScope, userPreferences.dataStore)
        }

        /**
         * Persist a new backend base URL via [SettingsRepository], reusing its normalisation
         * (trim + trailing-slash). Fire-and-forget on the ViewModel scope so a quick dialog
         * dismiss doesn't drop the write — DataStore serialises the edit on its own dispatcher.
         */
        fun setBackendBaseUrl(url: String) {
            viewModelScope.launch {
                settingsRepository.setBackendBaseUrl(url)
            }
        }

        /** Persist the direct-mode toggle. */
        fun setDirectMode(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.setDirectMode(enabled)
            }
        }

        /** Persist the user-supplied PTV `devid`. */
        fun setDevId(devId: String) {
            viewModelScope.launch {
                settingsRepository.setDevId(devId)
            }
        }

        /** Persist the user-supplied PTV API key. */
        fun setApiKey(apiKey: String) {
            viewModelScope.launch {
                settingsRepository.setApiKey(apiKey)
            }
        }

        private companion object {
            // Standard NIA value — matches the rest of the app's `WhileSubscribed` timeouts so
            // configuration changes don't drop the upstream subscription mid-rotation.
            const val STATE_FLOW_TIMEOUT_MILLIS: Long = 5_000
        }
    }

/**
 * Snapshot of the direct-mode UI state. Plain `data class` — three primitive fields don't justify
 * an Object Mother. Lives next to the ViewModel because the screen is the only consumer.
 */
data class DirectModeState(
    val enabled: Boolean,
    val devId: String,
    val apiKey: String,
) {
    companion object {
        /** Initial state — toggle off, no credentials. Used as `stateIn` seed. */
        val empty = DirectModeState(enabled = false, devId = "", apiKey = "")
    }
}
