/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.ui

import ac.jfx.openptv.core.data.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Top-level ViewModel for root-graph decisions — currently just the setup-completion gate.
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
