package ac.jfx.openptv.ui

import ac.jfx.openptv.core.data.FollowedTripRepository
import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.model.FollowedTrip
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        settings: SettingsRepository,
        val userPreferences: UserPreferencesDataStore,
        private val followedTripRepository: FollowedTripRepository,
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
    }

sealed interface GateState {
    data object Loading : GateState

    data object NeedsSetup : GateState

    data object Ready : GateState
}
