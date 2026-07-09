package ac.jfx.openptv.ui

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FollowedTripRepository
import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.domain.TripProgress
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RunPattern
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
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
 *
 * Also owns the followed-trip surface for the pinned "Return to your trip" bar (issue #200):
 * [followedTrip] is what the bar renders, [unfollowTrip] is its dismiss control, and
 * [evaluateFollowedTripCompletion] is the in-app auto-clear hook the root composable fires on
 * every resume. No background work — the follow is only ever evaluated while the app is open.
 *
 * The bar's live "Next stop" line (PR #202 follow-up) comes from [tripProgress]: while a trip
 * is followed *and* the app is foregrounded, [startTripProgressPolling] collects the followed
 * run's pattern through the same 30 s-polling [ObserveRunPatternUseCase] the run-pattern screen
 * uses (fetch-on-subscribe covers "refresh immediately on resume") and derives [TripProgress]
 * per emission. The root composable drives start/stop from `repeatOnLifecycle(RESUMED)`, so
 * backgrounding the app tears the poll down — no polling while nothing can see the bar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        settings: SettingsRepository,
        val userPreferences: UserPreferencesDataStore,
        private val followedTripRepository: FollowedTripRepository,
        private val observeRunPattern: ObserveRunPatternUseCase,
        private val clock: Clock,
    ) : ViewModel() {
        val gate: StateFlow<GateState> =
            settings.settings
                .map { if (it.setupCompleted) GateState.Ready else GateState.NeedsSetup }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = GateState.Loading,
                )

        /**
         * The trip the pinned bar shows, `null` when nothing is followed *or* the followed trip
         * is already complete — a stale follow never flashes the bar while the eviction below
         * catches up.
         */
        val followedTrip: StateFlow<FollowedTrip?> =
            followedTripRepository.followedTrip
                .map { trip -> trip?.takeUnless { it.isComplete(clock.now()) } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )

        /**
         * Live progress of the followed run for the bar's "Next stop" line, or `null` when
         * nothing is followed, nothing has been fetched yet, or the run has no upcoming stops
         * left. Deliberately *sticky across fetch failures*: an error or in-flight tick keeps
         * the last derived value, so the bar degrades to slightly-stale text instead of
         * flickering — the bar must never surface an error state.
         */
        val tripProgress: StateFlow<TripProgress?>
            get() = mutableTripProgress.asStateFlow()

        private val mutableTripProgress = MutableStateFlow<TripProgress?>(null)

        /** Tracks the active progress-polling coroutine so [startTripProgressPolling] is idempotent. */
        private var progressJob: Job? = null

        init {
            // Evict a completed trip from storage whenever the repository emits one — covers
            // the cold-start case where the app relaunches long after the trip finished.
            viewModelScope.launch {
                followedTripRepository.followedTrip.collect { trip ->
                    if (trip != null && trip.isComplete(clock.now())) {
                        followedTripRepository.unfollow()
                    }
                }
            }
        }

        /**
         * Re-check the stored trip against the wall clock. The repository flow only emits on
         * *writes*, so a trip that completes while the app sits in the background needs this
         * push — the root composable calls it on every ON_RESUME.
         */
        fun evaluateFollowedTripCompletion() {
            viewModelScope.launch {
                val trip = followedTripRepository.followedTrip.first()
                if (trip != null && trip.isComplete(clock.now())) {
                    followedTripRepository.unfollow()
                }
            }
        }

        /** The pinned bar's dismiss control. */
        fun unfollowTrip() {
            viewModelScope.launch { followedTripRepository.unfollow() }
        }

        /**
         * Start (or restart) the trip-progress poll behind the bar's "Next stop" line. Called
         * by the root composable on entering RESUMED; [stopTripProgressPolling] on leaving.
         *
         * Shape: the stored trip keyed by run → `flatMapLatest` into the polling pattern flow,
         * so unfollowing stops the poll, following a *different* run swaps it, and re-writes of
         * the same run (e.g. `completesAtUtc` refreshes) don't restart it. Each subscription
         * resets the progress to `null` first so a stale "Next stop" from a previous run never
         * lingers under a new trip's label. `Loading` / `Error` emissions are ignored — the
         * last good progress stays up (see [tripProgress]).
         */
        fun startTripProgressPolling() {
            progressJob?.cancel()
            progressJob =
                viewModelScope.launch {
                    followedTripRepository.followedTrip
                        .map { trip -> trip?.let { it.runRef to it.routeType } }
                        .distinctUntilChanged()
                        .flatMapLatest { key ->
                            if (key == null) {
                                flow { emit(ProgressUpdate.Reset) }
                            } else {
                                observeRunPattern(key.first, key.second)
                                    .map<Result<RunPattern>, ProgressUpdate> { ProgressUpdate.Emission(it) }
                                    .onStart { emit(ProgressUpdate.Reset) }
                            }
                        }
                        .collect { update ->
                            when (update) {
                                ProgressUpdate.Reset -> mutableTripProgress.value = null
                                is ProgressUpdate.Emission -> {
                                    val result = update.result
                                    if (result is Result.Success) {
                                        mutableTripProgress.value =
                                            TripProgress.from(result.data, clock.now())
                                    }
                                }
                            }
                        }
                }
        }

        /** Tear the progress poll down — the app left the foreground. */
        fun stopTripProgressPolling() {
            progressJob?.cancel()
            progressJob = null
        }

        /**
         * What the progress collector reacts to: a fresh pattern emission for the followed
         * run, or a reset because there is no (or a different) followed run to poll.
         */
        private sealed interface ProgressUpdate {
            data object Reset : ProgressUpdate

            data class Emission(
                val result: Result<RunPattern>,
            ) : ProgressUpdate
        }
    }

sealed interface GateState {
    data object Loading : GateState

    data object NeedsSetup : GateState

    data object Ready : GateState
}
