package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FollowedTripRepository
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.model.AlightAlert
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunPatternStop
import ac.jfx.openptv.core.model.RunRef
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import retrofit2.HttpException
import java.io.IOException

/**
 * Run-pattern ViewModel (issue #132). Owns a 30 s polling Flow of the run's stopping pattern,
 * kicked off whenever the screen enters RESUMED and cancelled when it leaves — the same
 * [startObserving] / [stopObserving] / [refresh] driver contract as
 * [`StopDetailViewModel`](ac.jfx.openptv.feature.stopdetail).
 *
 * `runRef` / `routeType` / `fromStopId` are assisted so the Compose layer hands the destination
 * key in at navigate-time without round-tripping through `SavedStateHandle` (Navigation 3 alpha
 * doesn't wire NavKey fields into the saved state automatically).
 *
 * Error handling: an error mid-poll surfaces as [PatternState.Error] but the loop is *not*
 * broken — the repository keeps ticking, so the next 30 s emission can recover.
 */
@HiltViewModel(assistedFactory = RunPatternViewModel.Factory::class)
class RunPatternViewModel
    @AssistedInject
    constructor(
        @Assisted("runRef") private val runRefValue: String,
        @Assisted("routeTypeCode") private val routeTypeCode: Int,
        /**
         * The stop the user tapped through from, so its row renders the "this stop" marker.
         * `NO_FROM_STOP` (-1) stands in for `null` over the assisted boundary — Dagger
         * assisted-inject doesn't generate nullable primitive bindings cleanly; same trade as
         * stop-detail's empty-string sentinel.
         */
        @Assisted("fromStopId") private val fromStopIdValue: Int,
        private val observeRunPattern: ObserveRunPatternUseCase,
        private val followedTripRepository: FollowedTripRepository,
        private val clock: Clock,
        /**
         * Exposed for the Compose layer so the screen renders relative times under the same
         * injected clock the ViewModel uses for `asOf` and the past/future split.
         */
        internal val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        private val runRef: RunRef = RunRef(runRefValue)
        private val routeType: RouteType = RouteType.fromCode(routeTypeCode)
        private val fromStopId: StopId? =
            fromStopIdValue.takeIf { it != NO_FROM_STOP }?.let(::StopId)

        @AssistedFactory
        interface Factory {
            fun create(
                @Assisted("runRef") runRef: String,
                @Assisted("routeTypeCode") routeTypeCode: Int,
                @Assisted("fromStopId") fromStopId: Int,
            ): RunPatternViewModel
        }

        private val _uiState = MutableStateFlow(RunPatternUiState.Initial)
        val uiState: StateFlow<RunPatternUiState> = _uiState.asStateFlow()

        /** Tracks the active observation coroutine so [startObserving] is idempotent. */
        private var observeJob: Job? = null

        /**
         * The most recent successful pattern, kept so a follow action (issue #200) can be built
         * from real data — terminus arrival, route label, destination — without a second fetch.
         */
        private var latestPattern: RunPattern? = null

        /**
         * The stop an in-flight replace-confirmation wants to arm an alight alert on (issue
         * #201): set when arming raised the dialog, consumed by [confirmReplaceFollow], cleared
         * on dismiss or a plain follow.
         */
        private var pendingAlightStopId: StopId? = null

        init {
            // Mirror the followed-trip repository into the UI state for the whole ViewModel
            // lifetime (not just while the pattern poll runs) so the Follow/Unfollow action is
            // correct the moment the screen composes.
            viewModelScope.launch {
                followedTripRepository.followedTrip.collect { trip ->
                    val followsThisRun = trip?.runRef == runRef
                    _uiState.update {
                        it.copy(
                            isFollowingThisRun = followsThisRun,
                            alightStopId = trip?.alightAlert?.stopId.takeIf { _ -> followsThisRun },
                        )
                    }
                }
            }
        }

        /**
         * Kick off (or re-kick) the polling collection. Called from the UI inside a
         * `repeatOnLifecycle(Lifecycle.State.RESUMED)` block, so it runs on every Pause→Resume
         * cycle. Re-entry cancels the previous collector.
         */
        fun startObserving() {
            observeJob?.cancel()
            observeJob =
                viewModelScope.launch {
                    observeRunPattern(runRef, routeType).collect { result ->
                        if (result is Result.Success) {
                            latestPattern = result.data
                            syncFollowedTrip(result.data)
                        }
                        _uiState.update { current -> current.applyResult(result) }
                    }
                }
        }

        fun stopObserving() {
            observeJob?.cancel()
            observeJob = null
        }

        /**
         * Pull-to-refresh handler. Cancels the active collector and re-subscribes, which forces
         * a fresh fetch. Flips `isRefreshing` true; the next emission clears it.
         */
        fun refresh() {
            _uiState.update { it.copy(isRefreshing = true) }
            startObserving()
        }

        /**
         * Follow this trip (issue #200). If nothing is followed (or this run already is —
         * an upsert), the trip is stored immediately; if a *different* run is followed, the
         * replace-confirmation is raised instead and nothing is written until
         * [confirmReplaceFollow].
         *
         * No-op until the first successful pattern fetch: the stored trip needs the terminus
         * arrival + display labels, and the UI only shows the action once the pattern is Loaded.
         */
        fun followTrip() {
            val pattern = latestPattern ?: return
            if (pattern.stops.isEmpty()) return
            pendingAlightStopId = null
            viewModelScope.launch {
                val current = followedTripRepository.followedTrip.first()
                if (current != null && current.runRef != runRef) {
                    _uiState.update { it.copy(followReplaceCandidate = current) }
                } else {
                    followedTripRepository.follow(pattern.toFollowedTrip(existing = current))
                }
            }
        }

        /**
         * Arm the "I'm getting off here" alert on [stopId] (issue #201). Follows the trip if it
         * isn't already followed (raising the same replace-confirmation when a *different* run
         * is), and stores a fresh [AlightAlert] — arming a different stop while one is armed
         * re-arms with reset fire-once latches, because the alert is rebuilt from scratch.
         *
         * When the pattern has no live estimates (trams — see CLAUDE.md quirks), flips
         * [RunPatternUiState.alightLocationPromptNeeded] so the screen can contextually request
         * location permission for the GPS fallback.
         */
        fun armAlightAlert(stopId: StopId) {
            val pattern = latestPattern ?: return
            val alightStop = pattern.stops.lastOrNull { it.stopId == stopId } ?: return
            viewModelScope.launch {
                val current = followedTripRepository.followedTrip.first()
                if (current != null && current.runRef != runRef) {
                    pendingAlightStopId = stopId
                    _uiState.update { it.copy(followReplaceCandidate = current) }
                } else {
                    followedTripRepository.follow(
                        pattern.toFollowedTrip(existing = current, alightStop = alightStop),
                    )
                    if (pattern.lacksRealTimeSignal()) {
                        _uiState.update { it.copy(alightLocationPromptNeeded = true) }
                    }
                }
            }
        }

        /** Disarm the alight alert but keep following the trip. */
        fun disarmAlightAlert() {
            viewModelScope.launch {
                val current = followedTripRepository.followedTrip.first() ?: return@launch
                if (current.runRef != runRef || current.alightAlert == null) return@launch
                followedTripRepository.follow(current.copy(alightAlert = null))
            }
        }

        /** The screen has dealt with the location-permission prompt (granted, denied, or moot). */
        fun onAlightLocationPromptHandled() {
            _uiState.update { it.copy(alightLocationPromptNeeded = false) }
        }

        /** Confirm replacing the previously followed trip with this run. */
        fun confirmReplaceFollow() {
            val pattern = latestPattern ?: return
            if (pattern.stops.isEmpty()) return
            val alightStop = pendingAlightStopId?.let { id -> pattern.stops.lastOrNull { it.stopId == id } }
            pendingAlightStopId = null
            viewModelScope.launch {
                followedTripRepository.follow(
                    pattern.toFollowedTrip(existing = null, alightStop = alightStop),
                )
                _uiState.update {
                    it.copy(
                        followReplaceCandidate = null,
                        alightLocationPromptNeeded =
                            it.alightLocationPromptNeeded ||
                                (alightStop != null && pattern.lacksRealTimeSignal()),
                    )
                }
            }
        }

        /** Dismiss the replace confirmation without touching the stored trip. */
        fun dismissReplaceFollow() {
            pendingAlightStopId = null
            _uiState.update { it.copy(followReplaceCandidate = null) }
        }

        /** Stop following this trip. */
        fun unfollowTrip() {
            viewModelScope.launch { followedTripRepository.unfollow() }
        }

        /**
         * Keep the stored followed trip honest while its own pattern is on screen: every
         * successful fetch refreshes `completesAtUtc` (estimates drift) and the display labels.
         * Only writes when something actually changed, so the 30 s tick doesn't spam DataStore.
         */
        private suspend fun syncFollowedTrip(pattern: RunPattern) {
            if (pattern.stops.isEmpty()) return
            val followed = followedTripRepository.followedTrip.first() ?: return
            if (followed.runRef != runRef) return
            val terminus = pattern.stops.last()
            val updated =
                followed.copy(
                    routeLabel = pattern.route?.displayLabel ?: followed.routeLabel,
                    destinationName = pattern.directionName.ifBlank { followed.destinationName },
                    completesAtUtc = terminus.estimatedDepartureUtc ?: terminus.scheduledDepartureUtc,
                )
            if (updated != followed) followedTripRepository.follow(updated)
        }

        /**
         * Build the stored trip from the loaded pattern. When [existing] already follows this
         * run the write is an upsert: `followedAtUtc` and any armed alert survive. [alightStop]
         * (issue #201) arms a *fresh* [AlightAlert] — deliberately not carrying old latches, so
         * changing the alight stop re-arms both stages.
         */
        private fun RunPattern.toFollowedTrip(
            existing: FollowedTrip?,
            alightStop: RunPatternStop? = null,
        ): FollowedTrip {
            val terminus = stops.last()
            val sameRun = existing?.takeIf { it.runRef == runRef }
            return FollowedTrip(
                runRef = runRef,
                routeType = routeType,
                fromStopId = fromStopId,
                routeLabel = route?.displayLabel,
                destinationName = directionName,
                completesAtUtc = terminus.estimatedDepartureUtc ?: terminus.scheduledDepartureUtc,
                followedAtUtc = sameRun?.followedAtUtc ?: clock.now(),
                alightAlert = alightStop?.toAlightAlert() ?: sameRun?.alightAlert,
            )
        }

        private fun RunPatternStop.toAlightAlert(): AlightAlert =
            AlightAlert(
                stopId = stopId,
                stopName = stopName,
                coordinates = coordinates,
            )

        /** True when no stop carries a live estimate — the tram case the GPS fallback exists for. */
        private fun RunPattern.lacksRealTimeSignal(): Boolean = stops.none { it.estimatedDepartureUtc != null }

        private fun RunPatternUiState.applyResult(result: Result<RunPattern>): RunPatternUiState =
            when (result) {
                is Result.Loading ->
                    // Keep the previous timeline on screen during a background tick — flipping to
                    // a skeleton twice a minute would jiggle the list. The initial state is
                    // already Loading, so the first fetch still shows the skeleton.
                    this
                is Result.Success ->
                    copy(
                        pattern = result.data.toPatternState(),
                        isRefreshing = false,
                        asOf = clock.now(),
                    )
                is Result.Error ->
                    copy(
                        pattern = PatternState.Error(result.throwable.toUserFacingReason()),
                        isRefreshing = false,
                    )
            }

        private fun RunPattern.toPatternState(): PatternState {
            if (stops.isEmpty()) return PatternState.Empty
            val rows =
                stops.map { stop ->
                    PatternStopRow(
                        stop = stop,
                        hasDeparted =
                            timeFormatter.isDeparted(
                                scheduled = stop.scheduledDepartureUtc,
                                estimated = stop.estimatedDepartureUtc,
                            ),
                        isOrigin = fromStopId != null && stop.stopId == fromStopId,
                    )
                }
            return PatternState.Loaded(
                routeLabel = route?.displayLabel,
                directionName = directionName,
                stops = rows,
                firstUpcomingIndex = rows.indexOfFirst { !it.hasDeparted }.coerceAtLeast(0),
                mapData = toMapData(rows),
            )
        }

        /**
         * Project the run's geometry into [RunPatternMapData] for the collapsible map (issue #187).
         * Returns null when there's nothing to draw — no geopath *and* no stop has coordinates — so
         * the screen drops the map section instead of showing an empty tile. Markers are built only
         * for stops PTV gave a location for; a coordinate-less stop is silently skipped on the map
         * (it still renders in the timeline).
         */
        private fun RunPattern.toMapData(rows: List<PatternStopRow>): RunPatternMapData? {
            val markers =
                rows.mapNotNull { row ->
                    val coord = row.stop.coordinates ?: return@mapNotNull null
                    RunPatternMapMarker(
                        coordinates = coord,
                        label = row.stop.stopName,
                        isOrigin = row.isOrigin,
                        hasDeparted = row.hasDeparted,
                    )
                }
            val polyline = geopath
            if (polyline.all { it.isEmpty() } && markers.isEmpty()) return null
            return RunPatternMapData(
                routeType = routeType,
                polyline = polyline,
                markers = markers,
                bounds = RunPatternMapData.boundsOf(polyline, markers),
            )
        }

        private fun Throwable.toUserFacingReason(): String =
            when (this) {
                is HttpException ->
                    when (code()) {
                        in HTTP_CLIENT_ERROR_RANGE -> "Pattern request was rejected (${code()})."
                        in HTTP_SERVER_ERROR_RANGE -> "The proxy is having a bad time (${code()}). Try again."
                        else -> "Unexpected HTTP error (${code()})."
                    }
                is IOException -> "Couldn't reach the network. Check your connection."
                is kotlinx.serialization.SerializationException ->
                    "Response was malformed. The backend may be out of date."
                else -> message ?: "Something went wrong."
            }

        companion object {
            /** Sentinel for "no originating stop" over the assisted-inject boundary. */
            const val NO_FROM_STOP: Int = -1

            private val HTTP_CLIENT_ERROR_RANGE = 400..499
            private val HTTP_SERVER_ERROR_RANGE = 500..599
        }
    }
