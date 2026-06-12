package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
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
         * Kick off (or re-kick) the polling collection. Called from the UI inside a
         * `repeatOnLifecycle(Lifecycle.State.RESUMED)` block, so it runs on every Pause→Resume
         * cycle. Re-entry cancels the previous collector.
         */
        fun startObserving() {
            observeJob?.cancel()
            observeJob =
                viewModelScope.launch {
                    observeRunPattern(runRef, routeType).collect { result ->
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
