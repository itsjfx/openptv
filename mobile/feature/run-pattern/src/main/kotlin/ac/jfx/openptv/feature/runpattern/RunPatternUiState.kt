package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.model.RunPatternStop
import kotlinx.datetime.Instant

/**
 * UI state for the run-pattern screen (issue #132). One sealed lifecycle — unlike stop-detail
 * there's no independent header fetch; the title, the timeline and the as-of line all come from
 * the same polling Flow.
 *
 * [isRefreshing] is the pull-to-refresh indicator state: flips on while a manual refresh is in
 * flight, back off on the next emission. The background 30 s tick doesn't toggle it.
 *
 * `asOf` is the wall-clock instant of the most recent successful emission, rendered as
 * `As of HH:mm`. Null until the first successful fetch lands.
 */
data class RunPatternUiState(
    val pattern: PatternState,
    val isRefreshing: Boolean = false,
    val asOf: Instant? = null,
) {
    companion object {
        val Initial: RunPatternUiState = RunPatternUiState(pattern = PatternState.Loading)
    }
}

sealed interface PatternState {
    data object Loading : PatternState

    /**
     * The run's stopping pattern, projected for rendering. `routeLabel` is the user-facing route
     * name/number (null when PTV's sideload missed the route — the title falls back to the
     * generic screen title). `firstUpcomingIndex` is where the auto-scroll lands on first render:
     * the first stop the service hasn't departed yet, clamped to 0 when every stop has passed.
     */
    data class Loaded(
        val routeLabel: String?,
        val directionName: String,
        val stops: List<PatternStopRow>,
        val firstUpcomingIndex: Int,
    ) : PatternState

    /** A successful fetch returned zero pattern stops — stale `run_ref`, finished run. */
    data object Empty : PatternState

    data class Error(val reason: String) : PatternState
}

/**
 * One row on the timeline. `hasDeparted` drives the dimmed past-stop rendering; it's computed at
 * mapping time under the injected clock so the unit test pins the past/future split without
 * reaching into the UI tree. `isOrigin` marks the stop the user tapped through from
 * ("you are here").
 */
data class PatternStopRow(
    val stop: RunPatternStop,
    val hasDeparted: Boolean,
    val isOrigin: Boolean,
)
