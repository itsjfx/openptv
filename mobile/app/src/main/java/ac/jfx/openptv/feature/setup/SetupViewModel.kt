package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.core.data.SettingsRepository
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
 * The setup completes via [completeSetup] which writes the chosen URL and flips
 * `setupCompleted` to `true`. The root nav graph observes that flag and navigates away from
 * the setup screen as soon as the write commits.
 */
@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SetupUiState(defaultUrl = ""))
        val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val current = settings.settings.first()
                _uiState.update { it.copy(defaultUrl = current.backendBaseUrl) }
            }
        }

        fun onServerChoiceChanged(choice: ServerChoice) {
            _uiState.update { it.copy(serverChoice = choice) }
        }

        fun onCustomUrlChanged(url: String) {
            _uiState.update { it.copy(customUrl = url) }
        }

        fun onConsentToggled(accepted: Boolean) {
            _uiState.update { it.copy(consentAccepted = accepted) }
        }

        fun completeSetup(onDone: () -> Unit) {
            val state = _uiState.value
            if (!state.canContinue) return
            viewModelScope.launch {
                settings.completeSetup(state.effectiveUrl)
                onDone()
            }
        }
    }
