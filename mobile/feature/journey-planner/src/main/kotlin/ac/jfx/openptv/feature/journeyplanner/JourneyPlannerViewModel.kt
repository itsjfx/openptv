package ac.jfx.openptv.feature.journeyplanner

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FavouriteJourneysRepository
import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.data.JourneyPlannerRepository
import ac.jfx.openptv.core.data.StopSearchRepository
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Journey planner ViewModel (issue #204). Two reactive pipelines feed one [JourneyPlannerUiState]:
 *
 *  - **Picker**: `query → debounce(300 ms) → distinctUntilChanged → flatMapLatest { search }`,
 *    the exact `:feature:search` recipe, active only while a field is being picked. The raw
 *    query is combined into the UiState undebounced so the text field echoes keystrokes.
 *    The route-type filter (issue #213) combines in after the debounce so a chip tap re-runs
 *    the current search immediately; the same selection filters the favourite-stops idle list
 *    client-side. Session-scoped, empty set = all modes — the `:feature:search` semantics.
 *  - **Results**: `(origin, destination, selectedTime, retry) → flatMapLatest { fetch }`. Live
 *    "departing now" collects the repository's 30 s polling Flow; a pinned custom time is a
 *    static snapshot and gets a one-shot fetch instead (mirrors stop-detail's rule). Collection
 *    follows `WhileSubscribed`, so polling stops when the screen leaves the composition.
 *
 * Events come in as method calls per the unidirectional-state convention.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class JourneyPlannerViewModel
    @Inject
    constructor(
        private val journeyPlannerRepository: JourneyPlannerRepository,
        private val stopSearchRepository: StopSearchRepository,
        private val favouritesRepository: FavouritesRepository,
        private val favouriteJourneysRepository: FavouriteJourneysRepository,
        /** Exposed for the screen's per-row relative "in N min" label, same as stop-detail. */
        val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        private val origin = MutableStateFlow<Stop?>(null)
        private val destination = MutableStateFlow<Stop?>(null)
        private val selectedTime = MutableStateFlow<Instant?>(null)
        private val activeField = MutableStateFlow<JourneyField?>(null)
        private val query = MutableStateFlow("")
        private val routeTypeFilter = MutableStateFlow<Set<RouteType>>(emptySet())
        private val retryCounter = MutableStateFlow(0)

        /**
         * The user's favourite stops, derived from the destination-at-stop favourites (issue
         * #209): favourites are `(stopId, destinationKey)` pairs, so several favourites at one
         * stop collapse to a single picker row (`distinctBy` stop id, favourites order kept).
         */
        private val favouriteStops: Flow<List<Stop>> =
            favouritesRepository.observe().map { favourites ->
                favourites
                    .distinctBy { it.stopId }
                    .map { favourite ->
                        Stop(
                            id = favourite.stopId,
                            name = favourite.stopName,
                            suburb = favourite.stopSuburb,
                            routeType = favourite.routeType,
                            latitude = favourite.lat,
                            longitude = favourite.lng,
                        )
                    }
            }

        private val pickerState: Flow<StopPickerState> =
            combine(
                activeField,
                query.debounce(DEBOUNCE_MILLIS).distinctUntilChanged(),
                routeTypeFilter,
            ) { field, term, filter -> Triple(field, term, filter) }
                .distinctUntilChanged()
                .flatMapLatest { (field, term, filter) ->
                    when {
                        // Empty query → Idle carrying the favourite stops so the open picker
                        // offers common endpoints before any typing (issue #209). The chip
                        // filter applies client-side here — favourites are already local.
                        field == null || term.isEmpty() ->
                            favouriteStops.map<List<Stop>, StopPickerState> { stops ->
                                StopPickerState.Idle(stops.filteredBy(filter))
                            }
                        else ->
                            flow {
                                emit(StopPickerState.Loading)
                                emit(stopSearchRepository.searchStops(term, filter).toPickerState())
                            }
                    }
                }

        /** Reactive ★ state for the current pair; false while either endpoint is missing. */
        private val isFavouriteJourney: Flow<Boolean> =
            combine(origin, destination) { from, to -> from to to }
                .flatMapLatest { (from, to) ->
                    if (from == null || to == null) {
                        flowOf(false)
                    } else {
                        favouriteJourneysRepository.isFavourite(
                            originStopId = from.id,
                            destinationStopId = to.id,
                        )
                    }
                }

        private val resultsState: Flow<JourneyResultsState> =
            combine(origin, destination, selectedTime, retryCounter) { from, to, at, retry ->
                // `retry` rides along so bumping the counter defeats distinctUntilChanged and
                // re-subscribes the fetch with identical inputs.
                JourneyRequest(from, to, at, retry)
            }
                .distinctUntilChanged()
                .flatMapLatest { request ->
                    val from = request.origin
                    val to = request.destination
                    when {
                        from == null || to == null -> flowOf<JourneyResultsState>(JourneyResultsState.Idle)
                        // A pinned time is a static snapshot — one-shot, no polling.
                        request.at != null ->
                            flow {
                                emit(JourneyResultsState.Loading)
                                emit(journeyPlannerRepository.getJourneys(from, to, request.at).toResultsState())
                            }
                        else ->
                            journeyPlannerRepository
                                .observeJourneys(from, to)
                                .map { it.toResultsState() }
                    }
                }

        val uiState: StateFlow<JourneyPlannerUiState> =
            combine(
                combine(origin, destination, selectedTime) { from, to, at -> Triple(from, to, at) },
                combine(activeField, query, routeTypeFilter) { field, term, filter ->
                    Triple(field, term, filter)
                },
                pickerState,
                resultsState,
                isFavouriteJourney,
            ) { (from, to, at), (field, term, filter), picker, results, favourited ->
                JourneyPlannerUiState(
                    origin = from,
                    destination = to,
                    selectedTime = at,
                    activeField = field,
                    query = term,
                    routeTypeFilter = filter,
                    picker = picker,
                    results = results,
                    isFavouriteJourney = favourited,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_TIMEOUT_MILLIS),
                initialValue = JourneyPlannerUiState(),
            )

        /** Open the inline stop picker for [field]; the previous query is cleared. */
        fun onFieldSelected(field: JourneyField) {
            query.value = ""
            activeField.value = field
        }

        fun onPickerDismissed() {
            activeField.value = null
            query.value = ""
        }

        fun onQueryChanged(newQuery: String) {
            query.value = newQuery
        }

        /**
         * Toggle a mode chip in the picker (issue #213). Same semantics as `:feature:search`:
         * empty selection means "all modes" (no non-empty invariant — deselecting the last chip
         * widens back out), and the pipeline re-runs the current search immediately because the
         * filter combines in after the debounce. The selection also filters the favourite-stops
         * idle list client-side, and survives picker close/reopen for the ViewModel's lifetime.
         */
        fun onRouteTypeFilterToggled(routeType: RouteType) {
            // Unknown isn't a chip — it's a runtime fallback for unexpected wire codes.
            if (routeType == RouteType.Unknown) return
            routeTypeFilter.update { current ->
                if (current.contains(routeType)) current - routeType else current + routeType
            }
        }

        /** Commit the picked stop to whichever field the picker is open for, then close it. */
        fun onStopPicked(stop: Stop) {
            when (activeField.value) {
                JourneyField.Origin -> origin.value = stop
                JourneyField.Destination -> destination.value = stop
                null -> return
            }
            onPickerDismissed()
        }

        fun onSwapStops() {
            val from = origin.value
            origin.value = destination.value
            destination.value = from
        }

        /**
         * Clear one endpoint back to "Choose a stop" (issue #215). The other endpoint and any
         * pinned time are untouched; the results pipeline maps a missing endpoint to Idle, which
         * also cancels the live poll for the old pair via flatMapLatest.
         */
        fun onStopCleared(field: JourneyField) {
            when (field) {
                JourneyField.Origin -> origin.value = null
                JourneyField.Destination -> destination.value = null
            }
        }

        fun onTimeSelected(instant: Instant) {
            selectedTime.value = instant
        }

        fun onTimeCleared() {
            selectedTime.value = null
        }

        /** Re-subscribes the results pipeline after an error without touching the inputs. */
        fun onRetry() {
            retryCounter.update { it + 1 }
        }

        /**
         * Star/unstar the current origin→destination pair (issue #209). No-op unless both
         * endpoints are set — the screen only renders the ★ once they are.
         */
        fun onToggleFavouriteJourney() {
            val from = origin.value ?: return
            val to = destination.value ?: return
            viewModelScope.launch {
                favouriteJourneysRepository.toggle(origin = from, destination = to)
            }
        }

        /**
         * Programmatic endpoint prefill (issue #209): a journey-favourite tap on the Favourites
         * tab lands here with both stops. Closes any open picker, resets to "departing now",
         * and sets both endpoints in one shot so the results pipeline starts the live fetch.
         */
        fun onEndpointsPrefilled(
            newOrigin: Stop,
            newDestination: Stop,
        ) {
            activeField.value = null
            query.value = ""
            selectedTime.value = null
            origin.value = newOrigin
            destination.value = newDestination
        }

        /** Empty filter = all modes; otherwise keep only stops whose mode is selected. */
        private fun List<Stop>.filteredBy(selected: Set<RouteType>): List<Stop> =
            if (selected.isEmpty()) this else filter { it.routeType in selected }

        private fun Result<List<Stop>>.toPickerState(): StopPickerState =
            when (this) {
                is Result.Loading -> StopPickerState.Loading
                is Result.Success ->
                    if (data.isEmpty()) StopPickerState.Empty else StopPickerState.Results(data)
                is Result.Error -> StopPickerState.Error(throwable.toUserFacingReason())
            }

        private fun Result<List<JourneyOption>>.toResultsState(): JourneyResultsState =
            when (this) {
                is Result.Loading -> JourneyResultsState.Loading
                is Result.Success ->
                    if (data.isEmpty()) {
                        JourneyResultsState.NoDirectServices
                    } else {
                        JourneyResultsState.Loaded(data)
                    }
                is Result.Error -> JourneyResultsState.Error(throwable.toUserFacingReason())
            }

        private fun Throwable.toUserFacingReason(): String =
            when (this) {
                is HttpException ->
                    when (code()) {
                        in 400..499 -> "The request was rejected (${code()})."
                        in 500..599 -> "The proxy is having a bad time (${code()}). Try again."
                        else -> "Unexpected HTTP error (${code()})."
                    }
                is IOException -> "Couldn't reach the network. Check your connection."
                is kotlinx.serialization.SerializationException ->
                    "The response was malformed. The backend may be out of date."
                else -> message ?: "Something went wrong."
            }

        private data class JourneyRequest(
            val origin: Stop?,
            val destination: Stop?,
            val at: Instant?,
            val retry: Int,
        )

        companion object {
            private const val DEBOUNCE_MILLIS: Long = 300
            private const val STATE_TIMEOUT_MILLIS: Long = 5_000
        }
    }
