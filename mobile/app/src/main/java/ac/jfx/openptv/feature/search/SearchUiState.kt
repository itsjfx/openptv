package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.model.Stop

/**
 * UI state for the stop-search screen. Five mutually exclusive shapes, exposed as a single
 * [kotlinx.coroutines.flow.StateFlow] so Compose recomposes from one source of truth.
 *
 *  - [Idle] — query is shorter than [MIN_QUERY_LENGTH]; no work in flight, no results.
 *  - [Loading] — a debounced query is in flight.
 *  - [Empty] — query returned zero stops.
 *  - [Results] — query returned at least one stop.
 *  - [Error] — last attempt failed; [reason] is user-facing.
 */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Results(val stops: List<Stop>) : SearchUiState
    data class Error(val reason: String) : SearchUiState
}

/** Below this character count the screen sits in [SearchUiState.Idle] and never hits the network. */
const val MIN_QUERY_LENGTH: Int = 3
