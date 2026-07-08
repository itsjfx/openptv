package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The single trip the user is currently following (issue #200). Carries exactly what the
 * run-pattern destination needs to reopen itself ([runRef], [routeType], [fromStopId]) plus the
 * display fields the pinned "Return to your trip" bar renders without refetching anything
 * ([routeLabel], [destinationName]).
 *
 * `completesAtUtc` is the estimated-or-scheduled arrival at the *final* stop of the run's
 * pattern, captured when the trip is followed and refreshed whenever the run pattern reloads
 * while followed. It drives [isComplete] — the in-app auto-clear when the vehicle has finished
 * its run. There is no background polling; the value is only as fresh as the last time the
 * pattern screen was open (the stacked alight-alert issue #201 adds liveness).
 *
 * `fromStopId` is nullable for the same reason as the nav key's: the user may have opened the
 * pattern without tapping through from a specific stop.
 */
data class FollowedTrip(
    val runRef: RunRef,
    val routeType: RouteType,
    val fromStopId: StopId?,
    val routeLabel: String?,
    val destinationName: String,
    val completesAtUtc: Instant,
    val followedAtUtc: Instant,
) {
    /**
     * True once [now] is past the final pattern stop's arrival plus [COMPLETION_GRACE]. The
     * grace period absorbs small real-time drift — an "arriving 09:11" service that actually
     * pulls in at 09:13 shouldn't have its follow vanish while the user is still on board.
     */
    fun isComplete(now: Instant): Boolean = now > completesAtUtc + COMPLETION_GRACE

    companion object {
        /** How long after the final stop's arrival the follow survives before auto-clearing. */
        val COMPLETION_GRACE: Duration = 5.minutes
    }
}
