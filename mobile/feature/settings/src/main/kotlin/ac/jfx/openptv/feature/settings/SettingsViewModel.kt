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
 * the typed-DSL machinery — it owns its own `Flow<AppSettings>`, not a composition local. The
 * single [serverConfigState] flow exposes the proxy URL + direct-mode toggle + credentials so
 * the picker dialog can seed itself from one snapshot — no half-applied write the dialog could
 * observe mid-keystroke. Writes go through [saveServerSelection], which sequences the three
 * persists (direct flag, URL, credentials) in one place so the dialog has a single seam.
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
         * Snapshot of the persisted server configuration. One state object (rather than four
         * separate flows) keeps the toggle, URL, and credential fields in lock-step so the
         * picker dialog seeds from a consistent view of the world. `apiKey` rides this flow only
         * so the masked field can re-render after a write — composition locals deliberately
         * don't carry it (one fewer place the secret could leak).
         */
        val serverConfigState: Flow<ServerConfigState> =
            settingsRepository.settings
                .map {
                    ServerConfigState(
                        backendUrl = it.backendBaseUrl,
                        directMode = it.directMode,
                        devId = it.devId,
                        apiKey = it.apiKey,
                    )
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_FLOW_TIMEOUT_MILLIS),
                    initialValue = ServerConfigState.empty,
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
         * Persist the picker dialog's [ServerPickerState] in a single sequence of writes:
         *
         *  - **Default proxy** — directMode = false, backendBaseUrl = bundled default.
         *  - **Custom proxy**  — directMode = false, backendBaseUrl = user-typed URL.
         *  - **Direct PTV**   — directMode = true, devId + apiKey = user-typed creds. Leaves
         *    the previously-chosen proxy URL untouched so flipping back later still works
         *    without re-typing.
         *
         * Repository writes are fire-and-forget on the ViewModel scope so a quick dialog
         * dismiss doesn't drop the persist — DataStore serialises edits on its own dispatcher.
         */
        fun saveServerSelection(state: ServerPickerState) {
            viewModelScope.launch {
                when (state.choice) {
                    ServerChoice.Default -> {
                        settingsRepository.setDirectMode(false)
                        settingsRepository.setBackendBaseUrl(state.defaultUrl)
                    }
                    ServerChoice.Custom -> {
                        settingsRepository.setDirectMode(false)
                        settingsRepository.setBackendBaseUrl(state.customUrl)
                    }
                    ServerChoice.DirectPtv -> {
                        settingsRepository.setDevId(state.devId)
                        settingsRepository.setApiKey(state.apiKey)
                        settingsRepository.setDirectMode(true)
                    }
                }
            }
        }

        private companion object {
            // Standard NIA value — matches the rest of the app's `WhileSubscribed` timeouts so
            // configuration changes don't drop the upstream subscription mid-rotation.
            const val STATE_FLOW_TIMEOUT_MILLIS: Long = 5_000
        }
    }

/**
 * Snapshot of the persisted server configuration, surfaced to the Settings screen and used to
 * seed the picker dialog. Plain `data class` — four primitive fields don't justify an Object
 * Mother. Lives next to the ViewModel because the screen is the only consumer.
 */
data class ServerConfigState(
    val backendUrl: String,
    val directMode: Boolean,
    val devId: String,
    val apiKey: String,
) {
    companion object {
        /** Initial state — empty URL, direct mode off, no credentials. Used as `stateIn` seed. */
        val empty = ServerConfigState(backendUrl = "", directMode = false, devId = "", apiKey = "")
    }
}
