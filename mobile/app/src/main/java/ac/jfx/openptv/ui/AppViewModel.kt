package ac.jfx.openptv.ui

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Top-level ViewModel for root-graph decisions — currently the setup-completion gate plus a
 * passthrough handle to [UserPreferencesDataStore] so the root composable can write theme-mode
 * changes from the Home screen's cycle button without an extra Hilt entry-point shim.
 *
 * The state is a sealed [GateState] rather than a raw boolean so the UI can show a tiny loader
 * while DataStore reads the first value off disk, then animate into either the setup flow or
 * the main app. Without the third Loading state the first frame after process start would
 * always render the Setup screen for a few ms, even for returning users.
 */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        settings: SettingsRepository,
        val userPreferences: UserPreferencesDataStore,
    ) : ViewModel() {
        val gate: StateFlow<GateState> =
            settings.settings
                .map { if (it.setupCompleted) GateState.Ready else GateState.NeedsSetup }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = GateState.Loading,
                )
    }

sealed interface GateState {
    data object Loading : GateState

    data object NeedsSetup : GateState

    data object Ready : GateState
}
