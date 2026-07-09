package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.model.RunPattern
import kotlinx.datetime.Instant

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
 */
data class TripProgress(
    val nextStopName: String?,
) {
    companion object {
        /**
         * Derive the progress of a run at [now]. A stop counts as *upcoming* while its live
         * estimate (falling back to the scheduled time when PTV has no prediction — trams
         * never populate estimates on the pattern endpoint) is strictly after [now].
         */
        fun from(
            pattern: RunPattern,
            now: Instant,
        ): TripProgress {
            val nextStop =
                pattern.stops.firstOrNull { stop ->
                    (stop.estimatedDepartureUtc ?: stop.scheduledDepartureUtc) > now
                }
            return TripProgress(nextStopName = nextStop?.stopName)
        }
    }
}
