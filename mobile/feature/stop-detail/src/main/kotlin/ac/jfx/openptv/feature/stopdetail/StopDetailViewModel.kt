package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.domain.GetStopDetailUseCase
import ac.jfx.openptv.core.domain.LoadMoreDeparturesUseCase
import ac.jfx.openptv.core.domain.ObserveDeparturesUseCase
import ac.jfx.openptv.core.domain.ObserveFavouritesUseCase
import ac.jfx.openptv.core.domain.ToggleFavouriteUseCase
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import retrofit2.HttpException
import java.io.IOException

/**
 * Stop-detail ViewModel. Owns four things:
 *
 *  1. A one-shot header fetch on init ([loadHeader]). Re-runs on retry.
 *  2. A 30 s polling Flow of departures, kicked off whenever the screen enters
 *     [androidx.lifecycle.Lifecycle.State.RESUMED] and cancelled when it leaves. The UI driver is
 *     [startObserving] / [stopObserving]; the Compose layer wraps these in `repeatOnLifecycle`.
 *  3. Pull-to-refresh, which forces a new collection cycle ([refresh]).
 *  4. Pagination — per-group expansion ([toggleExpand]) and "scrolled to the tail" ([loadMore]).
 *     Pagination uses a separate read path that appends to the in-memory tail, deduping by
 *     `runRef`. The head poll keeps running underneath so the top of the list stays live.
 *
 * `Clock` and `RelativeTimeFormatter` come from Hilt's `SingletonComponent`; `stopId` and
 * `routeType` are assisted so the Compose layer can hand the destination key into the ViewModel
 * factory at navigate-time without round-tripping through `SavedStateHandle` (Navigation 3 alpha
 * doesn't wire NavKey fields into the saved state automatically the way Navigation 2 does).
 *
 * Error handling: an error mid-poll surfaces as `DeparturesState.Error` but the loop is *not*
 * broken — the underlying repository keeps ticking, so the next 30 s emission can recover. This
 * mirrors the contract spelled out in [`ac.jfx.openptv.core.data.DepartureRepository`].
 */
@HiltViewModel(assistedFactory = StopDetailViewModel.Factory::class)
@Suppress("LongParameterList") // ViewModel composes several use cases — splitting would be churn for no clarity win
class StopDetailViewModel
    @AssistedInject
    constructor(
        @Assisted("stopId") private val stopIdValue: Int,
        @Assisted("routeTypeCode") private val routeTypeCode: Int,
        private val getStopDetail: GetStopDetailUseCase,
        private val observeDepartures: ObserveDeparturesUseCase,
        private val loadMoreDepartures: LoadMoreDeparturesUseCase,
        private val observeFavourites: ObserveFavouritesUseCase,
        private val toggleFavourite: ToggleFavouriteUseCase,
        private val clock: Clock,
        /**
         * Exposed for the Compose layer so the screen renders relative times under the same
         * injected clock the ViewModel uses for `asOf`. `internal` so previews / tests can read it.
         */
        internal val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        private val stopId: StopId = StopId(stopIdValue)
        private val routeType: RouteType = RouteType.fromCode(routeTypeCode)

        /**
         * Assisted-injection factory. Takes raw `Int`s rather than the domain value classes
         * ([StopId], [RouteType]) because Dagger's assisted-inject codegen doesn't currently
         * deal with the mangled JVM names that Kotlin value classes use as method parameters
         * (the symptom is `not a valid name: create-…` at KSP time). Boxing to the value class
         * happens at the ViewModel boundary instead — same effect, no name-mangling.
         */
        @AssistedFactory
        interface Factory {
            fun create(
                @Assisted("stopId") stopId: Int,
                @Assisted("routeTypeCode") routeTypeCode: Int,
            ): StopDetailViewModel
        }

        private val _uiState = MutableStateFlow(StopDetailUiState.Initial)
        val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

        /** Tracks the active observation coroutine so `startObserving` is idempotent. */
        private var observeJob: Job? = null

        /** Tracks the active loadMore coroutine so concurrent scroll triggers coalesce. */
        private var loadMoreJob: Job? = null

        /**
         * Accumulated paginated departures — anything past the head-poll window. Stored keyed by
         * `runRef` so the merge step is O(1) per row and a row's existence is idempotent across
         * head re-emissions and overlapping page fetches.
         */
        private val pagedByRunRef: MutableMap<String, Departure> = mutableMapOf()

        /**
         * Most-recent successful head poll, retained so [rebuildGroups] can re-fold pages on top
         * of it without waiting for the next 30 s tick.
         */
        private var lastHeadPoll: List<Departure> = emptyList()

        /** Which group keys the user has expanded. Persists across head emissions. */
        private val expandedGroups: MutableSet<GroupKey> = mutableSetOf()

        /**
         * Snapshot of every `(routeId, directionId)` triple at the current stop the user has
         * favourited. Updated by the favourites flow; consumed when [rebuildGroups] projects each
         * `Group.isFavourite`. Kept as an in-memory `Set` so the per-group lookup is O(1) and the
         * cost of a favourites emission is one set rebuild rather than one DAO query per group.
         */
        private var favouriteKeys: Set<GroupKey> = emptySet()

        init {
            loadHeader()
            observeFavouritesAtThisStop()
        }

        /**
         * Kick off (or re-kick) the polling collection of [observeDepartures]. Called from the UI
         * inside a `repeatOnLifecycle(Lifecycle.State.RESUMED)` block, so it runs on every
         * Pause→Resume cycle. Idempotent — re-entry while a previous job is still active cancels
         * the previous one (mirrors the "fresh collector lifetime drives polling" contract).
         */
        fun startObserving() {
            observeJob?.cancel()
            observeJob =
                viewModelScope.launch {
                    observeDepartures(stopId, routeType).collect { result ->
                        _uiState.update { current -> current.applyDepartureResult(result) }
                    }
                }
        }

        fun stopObserving() {
            observeJob?.cancel()
            observeJob = null
        }

        /**
         * Pull-to-refresh handler. Cancels the active collector (the polling Flow is "hot" only
         * for as long as a collector is attached) and re-subscribes, which forces a fresh fetch.
         * Flips `isRefreshing` true; the next emission clears it.
         */
        fun refresh() {
            _uiState.update { it.copy(isRefreshing = true) }
            startObserving()
        }

        fun retryHeader() {
            _uiState.update { it.copy(header = HeaderState.Loading) }
            loadHeader()
        }

        /**
         * Toggle the per-group expanded state. Expanding for the first time kicks off a
         * [loadMore] so the user sees more than the head poll provides (the head poll only asks
         * for [ac.jfx.openptv.core.data.DepartureRepository.INITIAL_PAGE_SIZE_PER_ROUTE] rows
         * per route).
         */
        fun toggleExpand(key: GroupKey) {
            val nowExpanded = !expandedGroups.contains(key)
            if (nowExpanded) {
                expandedGroups += key
            } else {
                expandedGroups -= key
            }
            _uiState.update { it.copy(departures = it.departures.applyExpansion()) }
            if (nowExpanded) {
                loadMore()
            }
        }

        /**
         * Request the next page of departures. Called by the UI when the user scrolls past the
         * tail of the list, and by [toggleExpand] when a group is first opened. Coalesces
         * concurrent calls — if a page is already in flight, the trigger is a no-op.
         */
        fun loadMore() {
            if (loadMoreJob?.isActive == true) return
            val tail = currentTailAnchor() ?: return
            loadMoreJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(departures = it.departures.withLoadingMore(true)) }
                    val result = loadMoreDepartures(stopId, routeType, tail, PAGE_SIZE)
                    when (result) {
                        is Result.Success -> {
                            result.data.forEach { dep -> pagedByRunRef[dep.runRef.value] = dep }
                        }
                        is Result.Error, Result.Loading -> { /* swallow — head poll will recover */ }
                    }
                    _uiState.update { current ->
                        current.copy(
                            departures = current.departures.withLoadingMore(false),
                        ).rebuildGroups()
                    }
                }
        }

        /**
         * Subscribe to the global favourites flow and project it down to "which `(routeId,
         * directionId)` triples at *this* stop are favourited". Updates [favouriteKeys] and
         * re-runs [rebuildGroups] so the star fill state in the UI reflects external mutations
         * (favourites screen, widget) immediately.
         *
         * Scoped to [viewModelScope] rather than the per-Resume [observeJob] because favourites
         * are a small in-memory flow — there's no battery cost to keeping the collector alive
         * across the screen's Pause cycles, and not tearing down means we don't miss a star-state
         * change made on another screen while this one is backgrounded.
         */
        private fun observeFavouritesAtThisStop() {
            viewModelScope.launch {
                observeFavourites().collect { favourites ->
                    favouriteKeys =
                        favourites
                            .asSequence()
                            .filter { it.stopId == stopId }
                            .map { GroupKey(routeId = it.routeId.value, directionId = it.directionId.value) }
                            .toSet()
                    _uiState.update { current -> current.rebuildGroups() }
                }
            }
        }

        /**
         * Toggle the favourited state of the `(routeId, directionId)` group at this stop. The
         * call relies on the header being loaded (so we have a [StopDetail.stop] to enrich the
         * favourite with display fields) and on at least one departure existing in the group (so
         * we have a [Direction] name to cache). Both preconditions hold whenever the star
         * affordance is visible — the star is rendered inside a `GroupHeader`, which only exists
         * once a group has been built from a successful departures emission.
         *
         * No-op if either precondition isn't met — silently dropping the tap is the right call
         * because the affordance shouldn't be reachable in those cases.
         */
        fun toggleFavourite(
            routeId: RouteId,
            direction: Direction,
        ) {
            val header = _uiState.value.header as? HeaderState.Loaded ?: return
            val route =
                header.detail.servingRoutes.firstOrNull { it.id == routeId }
                    ?: return
            val stop = header.detail.stop
            viewModelScope.launch {
                toggleFavourite(stop = stop, route = route, direction = direction)
            }
        }

        private fun loadHeader() {
            viewModelScope.launch {
                val result: Result<StopDetail> = getStopDetail(stopId, routeType)
                _uiState.update { current ->
                    current.copy(
                        header =
                            when (result) {
                                is Result.Loading -> HeaderState.Loading
                                is Result.Success -> HeaderState.Loaded(result.data)
                                is Result.Error -> HeaderState.Error(result.throwable.toUserFacingReason())
                            },
                    ).rebuildGroups()
                }
            }
        }

        private fun StopDetailUiState.applyDepartureResult(result: Result<List<Departure>>): StopDetailUiState =
            when (result) {
                is Result.Loading -> copy(departures = DeparturesState.Loading)
                is Result.Success -> {
                    lastHeadPoll = result.data
                    val merged = mergeDepartures(headPoll = result.data)
                    val groups = merged.toGroupedList(currentHeader = header)
                    copy(
                        departures =
                            if (groups.isEmpty()) DeparturesState.Empty else DeparturesState.Loaded(groups),
                        isRefreshing = false,
                        asOf = clock.now(),
                    )
                }
                is Result.Error ->
                    copy(
                        departures = DeparturesState.Error(result.throwable.toUserFacingReason()),
                        isRefreshing = false,
                    )
            }

        /**
         * Merge the most-recent head poll with the accumulated page cache. The head poll is the
         * source of truth for the rows it covers (its `estimatedDepartureUtc` is freshest), so we
         * overlay it on top of [pagedByRunRef]. Any pages that referenced runs the head poll has
         * since dropped (because they departed) get garbage-collected by the "departed filter"
         * in [toGroupedList] further down, so the map doesn't grow unbounded over a long screen
         * session.
         */
        private fun mergeDepartures(headPoll: List<Departure>): List<Departure> {
            val map = LinkedHashMap<String, Departure>(pagedByRunRef.size + headPoll.size)
            pagedByRunRef.values.forEach { map[it.runRef.value] = it }
            headPoll.forEach { map[it.runRef.value] = it }
            return map.values.toList()
        }

        /**
         * Apply the latest [expandedGroups] set to whatever groups the UI is currently rendering.
         * Used by [toggleExpand] which mutates expansion *without* fetching new data —
         * re-running the full merge / sort would be wasteful when only one boolean changed.
         */
        private fun DeparturesState.applyExpansion(): DeparturesState =
            when (this) {
                is DeparturesState.Loaded ->
                    copy(
                        groups =
                            groups.map { g ->
                                g.copy(expanded = expandedGroups.contains(g.key))
                            },
                    )
                else -> this
            }

        private fun DeparturesState.withLoadingMore(value: Boolean): DeparturesState =
            when (this) {
                is DeparturesState.Loaded -> copy(isLoadingMore = value)
                else -> this
            }

        /**
         * Recompute the groups list from the current page cache. Called after [loadMore] lands
         * its page and after the header resolves (the route projection is needed to fill in
         * each group's badge). Idempotent when nothing has changed.
         */
        private fun StopDetailUiState.rebuildGroups(): StopDetailUiState {
            // Only run if we already have at least one departure to show — the head poll's
            // own emission will rebuild via `applyDepartureResult` otherwise, and we don't
            // want to overwrite a Loading/Error state with an artificial Empty.
            if (departures !is DeparturesState.Loaded && pagedByRunRef.isEmpty() && lastHeadPoll.isEmpty()) {
                return this
            }
            val merged = mergeDepartures(headPoll = lastHeadPoll)
            val groups = merged.toGroupedList(currentHeader = header)
            val newDepartures =
                if (groups.isEmpty()) DeparturesState.Empty else DeparturesState.Loaded(groups)
            return copy(departures = newDepartures)
        }

        /**
         * Anchor instant for the next [loadMore] call — the latest known departure across all
         * groups. Returns null if we have no rows yet (which means there's nothing to anchor
         * paging on; either wait for the head poll or skip the trigger).
         */
        private fun currentTailAnchor(): Instant? {
            val state = _uiState.value.departures as? DeparturesState.Loaded ?: return null
            val all = state.groups.flatMap { it.departures }
            return all.maxOfOrNull { it.effectiveDepartureUtc() }
        }

        /**
         * Group departures by (routeId, directionId), preserving insertion order within each
         * group. The header label uses the [Route] from the header payload when available; that's
         * what gives us "Route 19 · North Coburg" rather than "Route #19 · …". Groups are sorted
         * by the earliest departure in each group so the closest service surfaces at the top —
         * the exact "newer ones appear smoothly" criterion in the issue.
         */
        private fun List<Departure>.toGroupedList(currentHeader: HeaderState): List<Group> {
            val servingRoutes =
                when (currentHeader) {
                    is HeaderState.Loaded -> currentHeader.detail.servingRoutes.associateBy { it.id.value }
                    else -> emptyMap()
                }
            // Issue #30 acceptance criterion: departed entries drop off. Use the formatter's
            // own threshold so a row that would render as "now" is never filtered, and a row
            // that would render as "departed" is never shown. Also garbage-collects departed
            // rows from `pagedByRunRef` so it doesn't grow without bound over a long session.
            val filtered = filterNot { timeFormatter.isDeparted(it.scheduledDepartureUtc, it.estimatedDepartureUtc) }
            val keptRefs = filtered.map { it.runRef.value }.toHashSet()
            pagedByRunRef.keys.retainAll(keptRefs)
            return filtered
                .groupBy { GroupKey(it.routeId.value, it.direction.id.value) }
                .map { (key, departures) ->
                    val route = servingRoutes[key.routeId]
                    val routeNumber = route?.number?.ifBlank { route.name }.orEmpty().ifBlank { "#${key.routeId}" }
                    Group(
                        key = key,
                        route = route,
                        routeType = route?.routeType ?: routeType,
                        headerLabel = "Route $routeNumber · ${departures.first().direction.name}",
                        departures = departures.sortedBy { it.effectiveDepartureUtc() },
                        expanded = expandedGroups.contains(key),
                        isFavourite = favouriteKeys.contains(key),
                    )
                }
                .sortedBy { it.departures.first().effectiveDepartureUtc() }
        }

        private fun Throwable.toUserFacingReason(): String =
            when (this) {
                is HttpException ->
                    when (code()) {
                        in HTTP_CLIENT_ERROR_RANGE -> "Stop request was rejected (${code()})."
                        in HTTP_SERVER_ERROR_RANGE -> "The proxy is having a bad time (${code()}). Try again."
                        else -> "Unexpected HTTP error (${code()})."
                    }
                is IOException -> "Couldn't reach the network. Check your connection."
                is kotlinx.serialization.SerializationException ->
                    "Response was malformed. The backend may be out of date."
                else -> message ?: "Something went wrong."
            }

        private companion object {
            private val HTTP_CLIENT_ERROR_RANGE = 400..499
            private val HTTP_SERVER_ERROR_RANGE = 500..599
        }
    }

/** Best-known departure instant — real-time prediction wins, falls back to the timetable. */
internal fun Departure.effectiveDepartureUtc() = estimatedDepartureUtc ?: scheduledDepartureUtc
