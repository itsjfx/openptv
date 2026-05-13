package ac.jfx.openptv.feature.settings

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
 * Settings screen ViewModel. Loads the current backend URL on creation, lets the user edit it,
 * and persists the new value on Save.
 *
 * [SettingsUiState.dirty] is true when [SettingsUiState.draftUrl] differs from
 * [SettingsUiState.savedUrl] — controls the enabled state of the Save button.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val current = settings.settings.first().backendBaseUrl
                _uiState.update {
                    it.copy(savedUrl = current, draftUrl = current, loaded = true)
                }
            }
        }

        fun onDraftUrlChanged(url: String) {
            _uiState.update { it.copy(draftUrl = url) }
        }

        fun onSave() {
            val state = _uiState.value
            if (!state.dirty || state.draftUrl.isBlank()) return
            viewModelScope.launch {
                settings.setBackendBaseUrl(state.draftUrl)
                _uiState.update { it.copy(savedUrl = it.draftUrl) }
            }
        }
    }

data class SettingsUiState(
    val savedUrl: String = "",
    val draftUrl: String = "",
    val loaded: Boolean = false,
) {
    val dirty: Boolean = loaded && draftUrl != savedUrl
}
