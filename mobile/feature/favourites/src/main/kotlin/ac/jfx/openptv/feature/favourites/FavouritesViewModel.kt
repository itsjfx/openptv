package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.domain.LoadNextDepartureUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ReorderFavouritesUseCase
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.StopId
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
 * ViewModel for the favourites screen (issue #35). Owns three pieces of state:
 *
 *  1. The favourites flow + sort preference, combined and projected into a sorted list of
 *     [FavouriteRow]s for the screen.
 *  2. A per-favourite "next departure" cache — keyed by `(stopId, routeId, directionId)` — driven
 *     by a 60 s tick while RESUMED (mirrors stop-detail). Each tick fans out N parallel reads
 *     bounded by `Semaphore(4)` per the phase-04 spec.
 *  3. The transient [PendingUndo] bookkeeping for swipe-to-delete-with-undo.
 *
 * **Polling lifetime**: [startObserving] / [stopObserving] follow the same shape as
 * `:feature:stop-detail`'s `StopDetailViewModel` so the screen can wrap them in
 * `repeatOnLifecycle(RESUMED)`. The tick runs on `Dispatchers.IO` because each fan-out cycle does
 * up to `N` HTTP reads.
 *
 * **Sort impls**:
 *  - `Manual` — repository order (`position ASC`). The favourites flow already arrives sorted.
 *  - `Alphabetical` — by `stopName`, then `routeNumber`.
 *  - `Nearest` — disabled in the UI until Phase 05 lands location; we fall back to `Manual` if it
 *    somehow gets selected so the screen never renders an undefined ordering.
 *
 * **Undo**: on swipe-delete the VM stashes the row + its position in [PendingUndo] and calls
 * `repository.remove`. Tap-undo re-`add`s the favourite — at the tail of the list, because the
 * repository doesn't support insert-at-index today. Once it does, the VM will trail with a
 * `reorder(...)` to restore the original position (see PR body).
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
        private val userPreferences: UserPreferencesDataStore,
        private val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        /**
         * Cache of next-departure state per favourite. Mutated by the tick coroutine; the screen
         * reads it indirectly via the projection that merges this with the favourites list.
         */
        private val nextDepartures: MutableStateFlow<Map<FavouriteKey, NextDepartureState>> =
            MutableStateFlow(emptyMap())

        /** Transient pending-undo state. Cleared when the user taps undo or the snackbar times out. */
        private val pendingUndo: MutableStateFlow<PendingUndo?> = MutableStateFlow(null)

        /** Tracks the active 60 s polling job so `startObserving` is idempotent. */
        private var tickJob: Job? = null

        /**
         * Projected screen state. Combines:
         *   - favourites flow (the SSOT for the list),
         *   - persisted sort preference,
         *   - current next-departure cache,
         *   - pending undo state.
         *
         * `stateIn` with `Eagerly` so the UI sees [FavouritesUiState.Loading] on first frame and a
         * real list as soon as the repository emits, even if the screen subscribed late.
         */
        val uiState: StateFlow<FavouritesUiState> =
            combine(
                // `observeFavourites` is a `Flow` not a `StateFlow` — it may not have emitted yet
                // when this combine first subscribes. `onStart { emit(emptyList()) }` is the
                // smallest hammer that lets the combined projection produce a real
                // `FavouritesUiState` immediately, instead of staying in `Loading` until every
                // upstream has fired. Production sees the real list a frame later when the
                // repository's Room-backed flow emits.
                observeFavourites().onStart { emit(emptyList()) },
                // Same shape — `userPreferences.favouritesSort` is a `map`-derived flow off
                // DataStore; it emits as soon as DataStore reads the file, which on a cold
                // launch can take a few milliseconds. Seed with the typed default so the
                // combined flow doesn't gate on it.
                userPreferences.favouritesSort.onStart { emit(FavouritesSortPreference.default) },
                nextDepartures,
                pendingUndo,
            ) { favourites, sort, nexts, undo ->
                projectState(favourites = favourites, sort = sort, nexts = nexts, undo = undo)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = FavouritesUiState.Loading,
            )

        /**
         * Kick off the 60 s "next departure per favourite" polling tick. Called from the UI inside
         * `repeatOnLifecycle(RESUMED)` so the tick pauses when the screen is backgrounded.
         * Idempotent — re-entry while a previous job is active cancels the previous one.
         *
         * The first iteration runs immediately so the user sees a real label as soon as the first
         * fetch lands (rather than waiting 60 s after Resume).
         */
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

        /** Persist a new sort selection through the typed DSL. Re-emits via the combined flow. */
        fun onSortSelected(sort: FavouritesSortPreference) {
            // Nearest stays disabled in the UI until Phase 05, but if a future code path somehow
            // routes through here we want the persisted value to be honest. The screen's
            // projection still degrades Nearest → Manual at render time.
            sort.put(scope = viewModelScope, dataStore = userPreferences.dataStore)
        }

        /**
         * Apply a drag-reorder. The UI hands in the new row order (every row in the list — same
         * shape as the repository's `reorder` contract). Persisted in one transaction.
         */
        fun onReorder(orderedKeys: List<FavouriteKey>) {
            viewModelScope.launch {
                reorderFavourites(
                    orderedIds =
                        orderedKeys.map { Triple(it.stopId, it.routeId, it.directionId) },
                )
            }
        }

        /**
         * Swipe a row away — remove from the repository, stash the pending undo so the snackbar
         * can show. The screen calls [onUndoDelete] if the user taps undo, or [clearPendingUndo]
         * once the snackbar times out.
         */
        fun onSwipeDelete(key: FavouriteKey) {
            val current = uiState.value as? FavouritesUiState.Loaded ?: return
            val row = current.rows.firstOrNull { it.key == key } ?: return
            pendingUndo.value = PendingUndo(row = row, originalPosition = row.position)
            viewModelScope.launch {
                favouritesRepository.remove(
                    stopId = StopId(key.stopId),
                    routeId = RouteId(key.routeId),
                    directionId = DirectionId(key.directionId),
                )
            }
        }

        /**
         * Restore a swiped-away favourite. Re-`add`s through the repository — the favourite lands
         * at the tail of the list (the repository assigns `max(position) + 1`). Restoring at the
         * original index requires the repository to support insert-at-index, which is a follow-up
         * (see PR body); for now the simpler tail-insert is good enough for the user's "I tapped
         * delete by mistake" flow.
         */
        fun onUndoDelete() {
            val undo = pendingUndo.value ?: return
            val row = undo.row
            pendingUndo.value = null
            viewModelScope.launch {
                favouritesRepository.add(
                    stopId = StopId(row.key.stopId),
                    routeType = row.routeType,
                    routeId = RouteId(row.key.routeId),
                    directionId = DirectionId(row.key.directionId),
                    stopName = row.stopName,
                    stopSuburb = row.stopSuburb,
                    routeNumber = row.routeNumber,
                    routeName = row.routeName,
                    directionName = row.directionName,
                    // `lat` / `lng` aren't on the row projection — the favourites entity persists
                    // them but the screen doesn't render them, so we pass zeros and accept the
                    // restored row's geo will be stale until the next stop-detail visit
                    // re-favourites it. Acceptable for v1 because the only consumer of `lat/lng`
                    // is Phase 05's Nearest sort, which is disabled.
                    lat = 0.0,
                    lng = 0.0,
                )
            }
        }

        /** Called by the snackbar's dismiss callback once the undo window elapses. */
        fun clearPendingUndo() {
            pendingUndo.value = null
        }

        /**
         * Run one fan-out cycle of "next departure per favourite". Bounded by [PARALLEL_FETCH_LIMIT]
         * concurrent reads via a [Semaphore]. The favourites list is read once at the start of the
         * cycle; if the list changes mid-cycle, the next tick picks up the new state — we don't
         * try to be clever about partial mid-cycle invalidation.
         *
         * On per-favourite error: keep the previous [NextDepartureState.Loaded] if one exists
         * (avoid flicker on a transient miss); fall back to [NextDepartureState.Error] only if no
         * stale Loaded is available.
         */
        private suspend fun refreshNextDepartures() {
            val favourites: List<FavouriteRouteAtStop> =
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
                            // No explicit dispatcher switch — the per-stop departures repository
                            // does its own withContext(Dispatchers.IO) at the network boundary. Keep
                            // the fan-out on the calling dispatcher so unit tests with a
                            // `StandardTestDispatcher` can advance it deterministically with
                            // `advanceUntilIdle`.
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
            favourite: FavouriteRouteAtStop,
            previous: NextDepartureState?,
        ): NextDepartureState {
            val result =
                loadNextDeparture(
                    stopId = favourite.stopId,
                    routeType = favourite.routeType,
                    routeId = favourite.routeId,
                    directionId = favourite.directionId,
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
            favourites: List<FavouriteRouteAtStop>,
            sort: FavouritesSortPreference,
            nexts: Map<FavouriteKey, NextDepartureState>,
            undo: PendingUndo?,
        ): FavouritesUiState {
            if (favourites.isEmpty()) {
                // An active undo means the user just swiped — keep the screen in Loaded so the
                // snackbar can still render with a meaningful row count of zero. Empty otherwise.
                return if (undo != null) {
                    FavouritesUiState.Loaded(rows = emptyList(), sort = sort, pendingUndo = undo)
                } else {
                    FavouritesUiState.Empty
                }
            }
            val rows = favourites.map { it.toRow(nexts[it.toKey()] ?: NextDepartureState.Loading) }
            val sorted = applySort(rows, sort)
            return FavouritesUiState.Loaded(rows = sorted, sort = sort, pendingUndo = undo)
        }

        private fun applySort(
            rows: List<FavouriteRow>,
            sort: FavouritesSortPreference,
        ): List<FavouriteRow> =
            when (sort) {
                FavouritesSortPreference.Manual -> rows.sortedBy { it.position }
                FavouritesSortPreference.Alphabetical ->
                    rows.sortedWith(
                        compareBy({ it.stopName.lowercase() }, { it.routeNumber }),
                    )
                // Nearest is disabled in the UI; if a stale value somehow lands here, fall back to
                // Manual so we don't render an undefined order.
                FavouritesSortPreference.Nearest -> rows.sortedBy { it.position }
            }

        private fun FavouriteRouteAtStop.toKey(): FavouriteKey =
            FavouriteKey(
                stopId = stopId.value,
                routeId = routeId.value,
                directionId = directionId.value,
            )

        private fun FavouriteRouteAtStop.toRow(next: NextDepartureState): FavouriteRow =
            FavouriteRow(
                key = toKey(),
                routeType = routeType,
                stopName = stopName,
                stopSuburb = stopSuburb,
                routeNumber = routeNumber.ifBlank { "#${routeId.value}" },
                routeName = routeName,
                directionName = directionName,
                nextDeparture = next,
                position = position,
            )

        private companion object {
            /** Phase-04 spec: bound the per-tick fan-out to four concurrent reads. */
            private const val PARALLEL_FETCH_LIMIT: Int = 4

            /** 60 s tick interval. Mirrors stop-detail's polling cadence. */
            private val TICK_INTERVAL_MILLIS: Long = 60.seconds.inWholeMilliseconds

            /** Cap on the per-tick `Flow.first()` read so a stalled collector doesn't wedge the tick. */
            private const val SNAPSHOT_TIMEOUT_MILLIS: Long = 2_000
        }
    }
