package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
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
        private val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        private val nextDepartures: MutableStateFlow<Map<FavouriteKey, NextDepartureState>> =
            MutableStateFlow(emptyMap())

        private val pendingUndo: MutableStateFlow<PendingUndo?> = MutableStateFlow(null)

        private val editMode: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private var tickJob: Job? = null

        val uiState: StateFlow<FavouritesUiState> =
            combine(
                // `onStart` so the combined projection produces a real `FavouritesUiState`
                // immediately on first subscription, instead of staying in `Loading` until
                // every upstream has fired.
                observeFavourites().onStart { emit(emptyList()) },
                nextDepartures,
                pendingUndo,
                editMode,
                isRefreshing,
            ) { favourites, nexts, undo, edit, refreshing ->
                projectState(
                    favourites = favourites,
                    nexts = nexts,
                    undo = undo,
                    editMode = edit,
                    isRefreshing = refreshing,
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
            val results: Map<FavouriteKey, NextDepartureState> =
                coroutineScope {
                    favourites
                        .map { fav ->
                            async {
                                semaphore.withPermit {
                                    val key = fav.toKey()
                                    val state = fetchOne(fav, previous[key])
                                    key to state
                                }
                            }
                        }
                        .awaitAll()
                        .toMap()
                }
            nextDepartures.value = results
        }

        private suspend fun fetchOne(
            favourite: FavouriteDestinationAtStop,
            previous: NextDepartureState?,
        ): NextDepartureState {
            val result =
                loadNextDeparture(
                    stopId = favourite.stopId,
                    routeType = favourite.routeType,
                    destinationKey = favourite.destinationKey,
                )
            return when (result) {
                is Result.Success -> {
                    val dep = result.data
                    if (dep == null) {
                        NextDepartureState.Empty
                    } else {
                        NextDepartureState.Loaded(
                            relativeLabel =
                                timeFormatter.format(
                                    scheduled = dep.scheduledDepartureUtc,
                                    estimated = dep.estimatedDepartureUtc,
                                ),
                            scheduledUtc = dep.scheduledDepartureUtc,
                            estimatedUtc = dep.estimatedDepartureUtc,
                            routeBadge =
                                routeDisplayLabel(
                                    routeType = favourite.routeType,
                                    routeNumber = "",
                                    routeName = "",
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

        private fun projectState(
            favourites: List<FavouriteDestinationAtStop>,
            nexts: Map<FavouriteKey, NextDepartureState>,
            undo: PendingUndo?,
            editMode: Boolean,
            isRefreshing: Boolean,
        ): FavouritesUiState {
            if (favourites.isEmpty()) {
                return if (undo != null) {
                    FavouritesUiState.Loaded(
                        rows = emptyList(),
                        pendingUndo = undo,
                        editMode = editMode,
                        isRefreshing = isRefreshing,
                    )
                } else {
                    FavouritesUiState.Empty
                }
            }
            val rows =
                favourites
                    .map { it.toRow(nexts[it.toKey()] ?: NextDepartureState.Loading) }
                    .sortedBy { it.position }
            return FavouritesUiState.Loaded(
                rows = rows,
                pendingUndo = undo,
                editMode = editMode,
                isRefreshing = isRefreshing,
            )
        }

        private fun FavouriteDestinationAtStop.toKey(): FavouriteKey =
            FavouriteKey(stopId = stopId.value, destinationKey = destinationKey)

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
