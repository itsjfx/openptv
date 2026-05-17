package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.feature.settings.ServerChoice
import ac.jfx.openptv.feature.settings.ServerPickerState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the first-run setup screen. Seeds the default URL from the currently persisted value
 * (which itself falls back to [ac.jfx.openptv.BuildConfig.BACKEND_BASE_URL] when nothing is
 * stored — see `SettingsRepositoryImpl`).
 *
 * Server-picker state is the same shape as the Settings dialog uses, so the shared
 * `ServerPickerContent` composable renders identically on both surfaces. [completeSetup]
 * persists the full chosen config (URL + direct-mode flag + credentials) atomically and flips
 * `setupCompleted` to `true`. The root nav graph observes that flag and navigates away from
 * the setup screen as soon as the write commits.
 */
@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SetupUiState.initial())
        val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val current = settings.settings.first()
                _uiState.update {
                    it.copy(pickerState = it.pickerState.copy(defaultUrl = current.backendBaseUrl))
                }
            }
        }

        fun onPickerStateChanged(pickerState: ServerPickerState) {
            _uiState.update { it.copy(pickerState = pickerState) }
        }

        fun onConsentToggled(accepted: Boolean) {
            _uiState.update { it.copy(consentAccepted = accepted) }
        }

        fun completeSetup(onDone: () -> Unit) {
            val state = _uiState.value
            if (!state.canContinue) return
            val picker = state.pickerState
            viewModelScope.launch {
                when (picker.choice) {
                    ServerChoice.Default ->
                        settings.completeSetup(
                            url = picker.defaultUrl,
                            directMode = false,
                            devId = "",
                            apiKey = "",
                        )
                    ServerChoice.Custom ->
                        settings.completeSetup(
                            url = picker.customUrl,
                            directMode = false,
                            devId = "",
                            apiKey = "",
                        )
                    ServerChoice.DirectPtv ->
                        // Keep the bundled default URL alongside so flipping back to proxy
                        // mode later doesn't strand the user with a blank backend URL.
                        settings.completeSetup(
                            url = picker.defaultUrl,
                            directMode = true,
                            devId = picker.devId,
                            apiKey = picker.apiKey,
                        )
                }
                onDone()
            }
        }
    }
