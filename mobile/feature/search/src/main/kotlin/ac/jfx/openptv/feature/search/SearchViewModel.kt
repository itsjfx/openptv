package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopSearchRepository
import ac.jfx.openptv.core.model.RouteType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Search-screen ViewModel. Owns a [MutableStateFlow] of the current query and exposes a
 * [StateFlow] of [SearchUiState]. The reactive pipeline is:
 *
 *   (query → debounce(300 ms) → distinctUntilChanged) + filter → flatMapLatest { fetch }
 *
 *  - `debounce` waits for keystrokes to settle so a fast typist only hits the network once.
 *  - The route-type filter (issue #213) combines in *after* the debounce so a chip tap re-runs
 *    the current query immediately — only typing is debounced. `distinctUntilChanged` sits on
 *    the combined pair so neither a re-submitted term nor a redundant combine emission
 *    re-fetches.
 *  - `flatMapLatest` cancels the in-flight fetch the moment the query changes — exactly the
 *    behaviour the acceptance criteria call out ("backspacing cancels the in-flight call").
 *
 * The filter is session-scoped ViewModel state (not persisted, matching the issue's out-of-scope
 * list). Empty set means "all modes" — the repository omits the `route_types` parameter.
 *
 * Errors are mapped to short user-facing strings here so the Compose layer stays free of
 * `throwable.message` formatting. Strings live in code (not `strings.xml`) for the barebones
 * cut; they move to localized resources alongside the multi-module split.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: StopSearchRepository,
    ) : ViewModel() {
        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _routeTypeFilter = MutableStateFlow<Set<RouteType>>(emptySet())

        /** The selected mode chips; empty means "all modes" (no `route_types` on the wire). */
        val routeTypeFilter: StateFlow<Set<RouteType>> = _routeTypeFilter.asStateFlow()

        val uiState: StateFlow<SearchUiState> =
            combine(
                _query.debounce(DEBOUNCE_MILLIS).distinctUntilChanged(),
                _routeTypeFilter,
            ) { term, filter -> term to filter }
                .distinctUntilChanged()
                .flatMapLatest { (term, filter) ->
                    if (term.length < MIN_QUERY_LENGTH) {
                        flowOf<SearchUiState>(SearchUiState.Idle)
                    } else {
                        flow<SearchUiState> {
                            emit(SearchUiState.Loading)
                            emit(toUiState(repository.searchStops(term, filter)))
                        }
                    }
                }
                .onEach { /* leave a hook here for future analytics. */ }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
                    initialValue = SearchUiState.Idle,
                )

        /** Called by the Compose text field on every keystroke. */
        fun onQueryChanged(newQuery: String) {
            _query.value = newQuery
        }

        /**
         * Toggle a mode chip (issue #213). Unlike Nearby's map filter there's no non-empty
         * invariant: an empty selection means "all modes", so deselecting the last chip widens
         * the search back out instead of dead-ending at zero results. The pipeline re-runs the
         * current query immediately — the filter combines in after the debounce.
         */
        fun onRouteTypeFilterToggled(routeType: RouteType) {
            // Unknown isn't a chip — it's a runtime fallback for unexpected wire codes.
            if (routeType == RouteType.Unknown) return
            _routeTypeFilter.update { current ->
                if (current.contains(routeType)) current - routeType else current + routeType
            }
        }

        private fun toUiState(result: Result<List<ac.jfx.openptv.core.model.Stop>>): SearchUiState =
            when (result) {
                is Result.Loading -> SearchUiState.Loading
                is Result.Success ->
                    if (result.data.isEmpty()) {
                        SearchUiState.Empty
                    } else {
                        SearchUiState.Results(result.data)
                    }
                is Result.Error -> SearchUiState.Error(result.throwable.toUserFacingReason())
            }

        private fun Throwable.toUserFacingReason(): String =
            when (this) {
                is HttpException ->
                    when (code()) {
                        in 400..499 -> "Search request was rejected (${code()})."
                        in 500..599 -> "The proxy is having a bad time (${code()}). Try again."
                        else -> "Unexpected HTTP error (${code()})."
                    }
                is IOException -> "Couldn't reach the network. Check your connection."
                is kotlinx.serialization.SerializationException ->
                    "Search response was malformed. The backend may be out of date."
                else -> message ?: "Something went wrong."
            }

        companion object {
            private const val DEBOUNCE_MILLIS: Long = 300
            private const val STATE_TIMEOUT_MILLIS: Long = 5_000
        }
    }
