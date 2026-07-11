package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunPatternStop
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Live progress of a followed trip, derived purely from its run pattern (PR #202 follow-up):
 * what the pinned "Return to your trip" bar renders *beyond* the static route → destination
 * label. No Android types, no side effects — pattern + clock in, display facts out — so the
 * derivation is unit-testable without a ViewModel.
 *
 * @property nextStopName the name of the first pattern stop whose `estimated ?: scheduled`
 *   departure is still in the future — "where the vehicle is heading right now". Null when the
 *   pattern is empty or every stop is already in the past (the run is effectively over; the
 *   completion eviction in [ac.jfx.openptv.core.model.FollowedTrip.isComplete] catches up
 *   separately), in which case the bar simply drops the progress line.
 * @property alightEta rough time until the armed alight stop (issue #201): its
 *   `estimated ?: scheduled` arrival minus now. Null when no alight stop was asked about, the
 *   stop isn't on the pattern (stale run data), or its time has already passed — in every case
 *   the bar just drops the ETA fragment rather than showing a countdown to the past. The bar
 *   renders it as rough minutes; the alert *stages* stay the service's job
 *   ([AlightAlertEvaluator]), so this is display-only and carries no fire semantics.
 */
data class TripProgress(
    val nextStopName: String?,
    val alightEta: Duration? = null,
) {
    companion object {
        /**
         * Derive the progress of a run at [now]. A stop counts as *upcoming* while its live
         * estimate (falling back to the scheduled time when PTV has no prediction — trams
         * never populate estimates on the pattern endpoint) is strictly after [now].
         *
         * [alightStopId] is the armed "I'm getting off here" stop, when there is one. Where
         * the stop repeats on the pattern (city-loop runs call at the same station twice) the
         * first *upcoming* occurrence wins — same rule as [AlightAlertEvaluator].
         */
        fun from(
            pattern: RunPattern,
            now: Instant,
            alightStopId: StopId? = null,
        ): TripProgress {
            val nextStop = pattern.stops.firstOrNull { it.departureUtc > now }
            val alightEta =
                alightStopId
                    ?.let { id ->
                        pattern.stops.firstOrNull { it.stopId == id && it.departureUtc > now }
                    }
                    ?.let { it.departureUtc - now }
            return TripProgress(
                nextStopName = nextStop?.stopName,
                alightEta = alightEta,
            )
        }

        private val RunPatternStop.departureUtc: Instant
            get() = estimatedDepartureUtc ?: scheduledDepartureUtc
    }
}
