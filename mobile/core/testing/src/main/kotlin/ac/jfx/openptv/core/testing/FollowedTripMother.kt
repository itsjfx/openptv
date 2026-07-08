package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Object Mother for [FollowedTrip] (issue #200). Defaults align with [RunPatternMother]'s
 * three-stop Lilydale run under the fixed test-clock convention of `2026-05-14T09:00:00Z`:
 * the same run ref the ViewModel tests use, terminating at Flinders Street with the Mother's
 * final-stop estimate (`09:11`) as the completion instant — i.e. an *active* trip at the test
 * clock. Use [aCompletedFollowedTrip] for one whose grace period has long expired.
 */
class FollowedTripMother private constructor() {
    companion object {
        fun aFollowedTrip(): FollowedTripBuilder = FollowedTripBuilder()

        /** A trip that finished well before the test clock — past completion plus grace. */
        fun aCompletedFollowedTrip(): FollowedTripBuilder =
            FollowedTripBuilder()
                .withCompletesAtUtc(Instant.parse("2026-05-14T08:00:00Z"))
                .withFollowedAtUtc(Instant.parse("2026-05-14T07:30:00Z"))
    }

    class FollowedTripBuilder {
        private var runRef: String = DEFAULT_RUN_REF
        private var routeType: RouteType = RouteType.Train
        private var fromStopId: Int? = DEFAULT_FROM_STOP_ID
        private var routeLabel: String? = DEFAULT_ROUTE_LABEL
        private var destinationName: String = DEFAULT_DESTINATION
        private var completesAtUtc: Instant = DEFAULT_COMPLETES_AT
        private var followedAtUtc: Instant = DEFAULT_FOLLOWED_AT

        fun withRunRef(value: String) = apply { this.runRef = value }

        fun withRouteType(value: RouteType) = apply { this.routeType = value }

        fun withFromStopId(value: Int?) = apply { this.fromStopId = value }

        fun withRouteLabel(value: String?) = apply { this.routeLabel = value }

        fun withDestinationName(value: String) = apply { this.destinationName = value }

        fun withCompletesAtUtc(value: Instant) = apply { this.completesAtUtc = value }

        fun withFollowedAtUtc(value: Instant) = apply { this.followedAtUtc = value }

        fun build(): FollowedTrip =
            FollowedTrip(
                runRef = RunRef(runRef),
                routeType = routeType,
                fromStopId = fromStopId?.let(::StopId),
                routeLabel = routeLabel,
                destinationName = destinationName,
                completesAtUtc = completesAtUtc,
                followedAtUtc = followedAtUtc,
            )

        private companion object {
            private const val DEFAULT_RUN_REF = "953527"
            private const val DEFAULT_FROM_STOP_ID = 1162
            private const val DEFAULT_ROUTE_LABEL = "Lilydale"
            private const val DEFAULT_DESTINATION = "Flinders Street"
            private val DEFAULT_COMPLETES_AT: Instant = Instant.parse("2026-05-14T09:11:00Z")
            private val DEFAULT_FOLLOWED_AT: Instant = Instant.parse("2026-05-14T09:00:00Z")
        }
    }
}
