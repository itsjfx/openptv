package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FavouriteJourneysRepository
import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.data.JourneyPlannerRepository
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
import ac.jfx.openptv.core.model.FavouriteJourney
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.model.routeDisplayLabel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * ViewModel for the favourites screen. Owns:
 *
 *  1. The favourites flow projected into a sorted-by-position list of [FavouriteRow]s.
 *  2. A per-favourite "next departure" cache — keyed by `(stopId, destinationKey)` — driven by a
 *     60 s tick while RESUMED. Each tick fans out N parallel reads bounded by `Semaphore(4)`.
 *  3. The transient [PendingUndo] bookkeeping for delete-with-undo.
 *  4. Edit-mode toggle + pull-to-refresh flag.
 */
@HiltViewModel
@Suppress("LongParameterList") // composes several use cases / formatters — split adds no clarity
class FavouritesViewModel
    @Inject
    constructor(
        private val observeFavourites: ObserveFavouritesUseCase,
        private val reorderFavourites: ReorderFavouritesUseCase,
        private val loadNextDeparture: LoadNextDepartureUseCase,
        private val favouritesRepository: FavouritesRepository,
        private val favouriteJourneysRepository: FavouriteJourneysRepository,
        private val journeyPlannerRepository: JourneyPlannerRepository,
        private val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        private val nextDepartures: MutableStateFlow<Map<FavouriteKey, NextDepartureState>> =
            MutableStateFlow(emptyMap())

        /** Issue #209: per-journey-favourite "next direct service" cache, same tick as above. */
        private val journeyNextServices: MutableStateFlow<Map<JourneyFavouriteKey, JourneyNextServiceState>> =
            MutableStateFlow(emptyMap())

        private val pendingUndo: MutableStateFlow<PendingUndo?> = MutableStateFlow(null)

        private val editMode: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

        /**
         * Page-level custom time (issue #182). Null means live "now". Combined into [uiState] so the
         * chip reflects it and folded into every next-departure fetch so each row is relative to it.
         */
        private val selectedTime: MutableStateFlow<Instant?> = MutableStateFlow(null)

        private var tickJob: Job? = null

        val uiState: StateFlow<FavouritesUiState> =
            combine(
                // `onStart` so the combined projection produces a real `FavouritesUiState`
                // immediately on first subscription, instead of staying in `Loading` until
                // every upstream has fired. The journey favourites (issue #209) ride along in
                // the same slot via an inner combine — `combine` only takes five typed flows.
                observeFavourites().onStart { emit(emptyList()) }
                    .combine(
                        favouriteJourneysRepository.observe().onStart { emit(emptyList()) },
                    ) { favourites, journeys -> favourites to journeys },
                nextDepartures.combine(journeyNextServices) { nexts, journeyNexts -> nexts to journeyNexts },
                pendingUndo,
                editMode,
                // Same bundling for the two boolean-ish tail flows (refreshing + selectedTime).
                isRefreshing.combine(selectedTime) { refreshing, chosen -> refreshing to chosen },
            ) { (favourites, journeyFavourites), (nexts, journeyNexts), undo, edit, refreshingAndTime ->
                projectState(
                    favourites = favourites,
                    journeyFavourites = journeyFavourites,
                    nexts = nexts,
                    journeyNexts = journeyNexts,
                    undo = undo,
                    editMode = edit,
                    isRefreshing = refreshingAndTime.first,
                    selectedTime = refreshingAndTime.second,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = FavouritesUiState.Loading,
            )

        fun startObserving() {
            tickJob?.cancel()
            tickJob =
                viewModelScope.launch {
                    while (true) {
                        refreshNextDepartures()
                        refreshJourneyNextServices()
                        delay(TICK_INTERVAL_MILLIS)
                    }
                }
        }

        fun stopObserving() {
            tickJob?.cancel()
            tickJob = null
        }

        fun refresh() {
            viewModelScope.launch {
                isRefreshing.value = true
                try {
                    refreshNextDepartures()
                    refreshJourneyNextServices()
                } finally {
                    isRefreshing.value = false
                }
            }
        }

        fun setEditMode(value: Boolean) {
            editMode.value = value
        }

        fun toggleEditMode() {
            editMode.value = !editMode.value
        }

        fun onReorder(orderedKeys: List<FavouriteKey>) {
            viewModelScope.launch {
                reorderFavourites(
                    orderedKeys =
                        orderedKeys.map { it.stopId to it.destinationKey },
                )
            }
        }

        fun onSwipeDelete(key: FavouriteKey) {
            val current = uiState.value as? FavouritesUiState.Loaded ?: return
            val row = current.rows.firstOrNull { it.key == key } ?: return
            pendingUndo.value = PendingUndo(row = row, originalPosition = row.position)
            viewModelScope.launch {
                favouritesRepository.remove(
                    stopId = StopId(key.stopId),
                    destinationKey = key.destinationKey,
                )
            }
        }

        fun onUndoDelete() {
            val undo = pendingUndo.value ?: return
            val row = undo.row
            pendingUndo.value = null
            viewModelScope.launch {
                favouritesRepository.add(
                    stopId = StopId(row.key.stopId),
                    destinationKey = row.key.destinationKey,
                    routeType = row.routeType,
                    stopName = row.stopName,
                    stopSuburb = row.stopSuburb,
                    destinationName = row.destinationName,
                    lat = row.lat,
                    lng = row.lng,
                )
            }
        }

        fun clearPendingUndo() {
            pendingUndo.value = null
        }

        /**
         * Pin every row's next-departure to a custom instant (issue #182). Sets the page-level
         * anchor and immediately re-fans so the rows update without waiting for the 60 s tick.
         */
        fun setSelectedTime(instant: Instant) {
            selectedTime.value = instant
            viewModelScope.launch {
                refreshNextDepartures()
                refreshJourneyNextServices()
            }
        }

        /** Reset the favourites page back to live "now" (issue #182) and re-fan immediately. */
        fun clearSelectedTime() {
            if (selectedTime.value == null) return
            selectedTime.value = null
            viewModelScope.launch {
                refreshNextDepartures()
                refreshJourneyNextServices()
            }
        }

        private suspend fun refreshNextDepartures() {
            val favourites: List<FavouriteDestinationAtStop> =
                withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) {
                    observeFavourites().first()
                } ?: emptyList()
            if (favourites.isEmpty()) {
                nextDepartures.value = emptyMap()
                return
            }
            val semaphore = Semaphore(PARALLEL_FETCH_LIMIT)
            val previous = nextDepartures.value
            // Snapshot the anchor once per fan-out so every row in this tick is computed against the
            // same instant (issue #182). The 60 s tick re-reads it each pass, so a pinned time keeps
            // refreshing at that anchor rather than snapping to now.
            val anchor = selectedTime.value
            val results: Map<FavouriteKey, NextDepartureState> =
                coroutineScope {
                    favourites
                        .map { fav ->
                            async {
                                semaphore.withPermit {
                                    val key = fav.toKey()
                                    val state = fetchOne(fav, previous[key], anchor)
                                    key to state
                                }
                            }
                        }
                        .awaitAll()
                        .toMap()
                }
            nextDepartures.value = results
        }

        /**
         * Issue #209: fan out one-shot `getJourneys` fetches for the journey favourites — same
         * bounded-parallelism recipe as [refreshNextDepartures], same anchor rules (a pinned
         * page-level time carries into every fetch). One-shot per tick, never a per-row
         * polling loop.
         */
        private suspend fun refreshJourneyNextServices() {
            val journeys: List<FavouriteJourney> =
                withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) {
                    favouriteJourneysRepository.observe().first()
                } ?: emptyList()
            if (journeys.isEmpty()) {
                journeyNextServices.value = emptyMap()
                return
            }
            val semaphore = Semaphore(PARALLEL_FETCH_LIMIT)
            val previous = journeyNextServices.value
            val anchor = selectedTime.value
            val results: Map<JourneyFavouriteKey, JourneyNextServiceState> =
                coroutineScope {
                    journeys
                        .map { journey ->
                            async {
                                semaphore.withPermit {
                                    val key = journey.toKey()
                                    key to fetchNextService(journey, previous[key], anchor)
                                }
                            }
                        }
                        .awaitAll()
                        .toMap()
                }
            journeyNextServices.value = results
        }

        private suspend fun fetchNextService(
            journey: FavouriteJourney,
            previous: JourneyNextServiceState?,
            at: Instant?,
        ): JourneyNextServiceState {
            val result =
                journeyPlannerRepository.getJourneys(
                    origin = journey.origin,
                    destination = journey.destination,
                    at = at,
                )
            return when (result) {
                is Result.Success -> {
                    // The repository returns options in departure order, but don't rely on it —
                    // the row promises the *soonest* boardable service.
                    val next = result.data.minByOrNull { it.effectiveDepartureUtc }
                    if (next == null) {
                        JourneyNextServiceState.Empty
                    } else {
                        JourneyNextServiceState.Loaded(
                            routeBadge = next.route.displayLabel,
                            directionName = next.direction.name,
                            relativeLabel =
                                timeFormatter.format(
                                    scheduled = next.scheduledDepartureUtc,
                                    estimated = next.estimatedDepartureUtc,
                                ),
                            scheduledDepartureUtc = next.scheduledDepartureUtc,
                            estimatedDepartureUtc = next.estimatedDepartureUtc,
                            departurePlatform = next.departurePlatform?.value,
                            arrivalUtc = next.effectiveArrivalUtc,
                            durationMinutes =
                                (next.effectiveArrivalUtc - next.effectiveDepartureUtc).inWholeMinutes,
                        )
                    }
                }
                is Result.Error ->
                    // Same no-flicker rule as the stop rows: keep the prior Loaded across a
                    // transient failure, otherwise degrade inline.
                    (previous as? JourneyNextServiceState.Loaded) ?: JourneyNextServiceState.Error
                Result.Loading -> previous ?: JourneyNextServiceState.Loading
            }
        }

        private suspend fun fetchOne(
            favourite: FavouriteDestinationAtStop,
            previous: NextDepartureState?,
            at: Instant?,
        ): NextDepartureState {
            val result =
                loadNextDeparture(
                    stopId = favourite.stopId,
                    routeType = favourite.routeType,
                    destinationKey = favourite.destinationKey,
                    at = at,
                )
            return when (result) {
                is Result.Success -> {
                    val next = result.data
                    if (next == null) {
                        NextDepartureState.Empty
                    } else {
                        val dep = next.departure
                        val route = next.route
                        NextDepartureState.Loaded(
                            relativeLabel =
                                timeFormatter.format(
                                    scheduled = dep.scheduledDepartureUtc,
                                    estimated = dep.estimatedDepartureUtc,
                                ),
                            scheduledUtc = dep.scheduledDepartureUtc,
                            estimatedUtc = dep.estimatedDepartureUtc,
                            // Source the badge from the joined Route so the label is the line name
                            // ("Belgrave") rather than `#<routeId>` — issue #137 regression. Falls
                            // back to `#<routeId>` only when PTV omitted the route sideload row.
                            routeBadge =
                                routeDisplayLabel(
                                    routeType = favourite.routeType,
                                    routeNumber = route?.number.orEmpty(),
                                    routeName = route?.name.orEmpty(),
                                    routeId = dep.routeId,
                                ),
                            routeName = dep.direction.name,
                        )
                    }
                }
                is Result.Error ->
                    // Keep the prior Loaded label across a transient failure so the row doesn't
                    // flicker. If no prior Loaded exists, surface the Error.
                    (previous as? NextDepartureState.Loaded) ?: NextDepartureState.Error
                Result.Loading -> previous ?: NextDepartureState.Loading
            }
        }

        @Suppress("LongParameterList") // projection folds every upstream flow into one state
        private fun projectState(
            favourites: List<FavouriteDestinationAtStop>,
            journeyFavourites: List<FavouriteJourney>,
            nexts: Map<FavouriteKey, NextDepartureState>,
            journeyNexts: Map<JourneyFavouriteKey, JourneyNextServiceState>,
            undo: PendingUndo?,
            editMode: Boolean,
            isRefreshing: Boolean,
            selectedTime: Instant?,
        ): FavouritesUiState {
            // Empty means *nothing* starred at all — stop favourites and journey favourites both
            // gone (issue #209). A pending undo keeps the Loaded shape so the snackbar survives.
            if (favourites.isEmpty() && journeyFavourites.isEmpty() && undo == null) {
                return FavouritesUiState.Empty
            }
            val rows =
                favourites
                    .map { it.toRow(nexts[it.toKey()] ?: NextDepartureState.Loading) }
                    .sortedBy { it.position }
            val journeyRows =
                journeyFavourites.map { journey ->
                    JourneyFavouriteRow(
                        key = journey.toKey(),
                        origin = journey.origin,
                        destination = journey.destination,
                        nextService = journeyNexts[journey.toKey()] ?: JourneyNextServiceState.Loading,
                    )
                }
            return FavouritesUiState.Loaded(
                rows = rows,
                journeyRows = journeyRows,
                pendingUndo = undo,
                editMode = editMode,
                isRefreshing = isRefreshing,
                selectedTime = selectedTime,
            )
        }

        private fun FavouriteDestinationAtStop.toKey(): FavouriteKey =
            FavouriteKey(stopId = stopId.value, destinationKey = destinationKey)

        private fun FavouriteJourney.toKey(): JourneyFavouriteKey =
            JourneyFavouriteKey(
                originStopId = origin.id.value,
                destinationStopId = destination.id.value,
            )

        private fun FavouriteDestinationAtStop.toRow(next: NextDepartureState): FavouriteRow =
            FavouriteRow(
                key = toKey(),
                routeType = routeType,
                stopName = stopName,
                stopSuburb = stopSuburb,
                destinationName = destinationName,
                nextDeparture = next,
                position = position,
                lat = lat,
                lng = lng,
            )

        private companion object {
            private const val PARALLEL_FETCH_LIMIT: Int = 4

            private val TICK_INTERVAL_MILLIS: Long = 60.seconds.inWholeMilliseconds

            private const val SNAPSHOT_TIMEOUT_MILLIS: Long = 2_000
        }
    }
