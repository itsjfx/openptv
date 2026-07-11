package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.Disruption
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.PlatformNumber
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RunRef
import kotlinx.datetime.Instant

/**
 * Object Mother for [JourneyOption] test fixtures (issue #204). Calling `.build()` with no
 * overrides yields a Lilydale-line run departing Richmond at `2026-05-14T09:07:00Z` (estimate
 * +1 min, platform `4`) and arriving at Burnley at `09:10:00Z` (estimate +1 min) — the corridor
 * the journey derivation was validated on. See `~/.claude/skills/object-mother/skill.md`.
 */
class JourneyOptionMother private constructor() {
    companion object {
        private val DEFAULT_ROUTE: Route = RouteMother.aRoute().withId(9).withName("Lilydale").build()
        private const val DEFAULT_DIRECTION_ID = 8
        private const val DEFAULT_DIRECTION_NAME = "Lilydale"
        private const val DEFAULT_RUN_REF = "951825"
        private val DEFAULT_SCHEDULED_DEPARTURE: Instant = Instant.parse("2026-05-14T09:07:00Z")
        private val DEFAULT_ESTIMATED_DEPARTURE: Instant = Instant.parse("2026-05-14T09:08:00Z")
        private const val DEFAULT_PLATFORM = "4"
        private val DEFAULT_SCHEDULED_ARRIVAL: Instant = Instant.parse("2026-05-14T09:10:00Z")
        private val DEFAULT_ESTIMATED_ARRIVAL: Instant = Instant.parse("2026-05-14T09:11:00Z")

        fun aJourneyOption(): JourneyOptionBuilder = JourneyOptionBuilder()

        /** No real-time predictions at either end — the tram case, scheduled times only. */
        fun aScheduledOnlyJourneyOption(): JourneyOptionBuilder =
            JourneyOptionBuilder()
                .withEstimatedDepartureUtc(null)
                .withEstimatedArrivalUtc(null)
    }

    class JourneyOptionBuilder {
        private var route: Route = DEFAULT_ROUTE
        private var directionId: Int = DEFAULT_DIRECTION_ID
        private var directionName: String = DEFAULT_DIRECTION_NAME
        private var runRef: String = DEFAULT_RUN_REF
        private var scheduledDepartureUtc: Instant = DEFAULT_SCHEDULED_DEPARTURE
        private var estimatedDepartureUtc: Instant? = DEFAULT_ESTIMATED_DEPARTURE
        private var departurePlatform: String? = DEFAULT_PLATFORM
        private var scheduledArrivalUtc: Instant = DEFAULT_SCHEDULED_ARRIVAL
        private var estimatedArrivalUtc: Instant? = DEFAULT_ESTIMATED_ARRIVAL
        private var disruptions: List<Disruption> = emptyList()

        fun withRoute(value: Route) = apply { this.route = value }

        fun withDirectionId(value: Int) = apply { this.directionId = value }

        fun withDirectionName(value: String) = apply { this.directionName = value }

        fun withRunRef(value: String) = apply { this.runRef = value }

        fun withScheduledDepartureUtc(value: Instant) = apply { this.scheduledDepartureUtc = value }

        fun withEstimatedDepartureUtc(value: Instant?) = apply { this.estimatedDepartureUtc = value }

        fun withDeparturePlatform(value: String?) = apply { this.departurePlatform = value }

        fun withScheduledArrivalUtc(value: Instant) = apply { this.scheduledArrivalUtc = value }

        fun withEstimatedArrivalUtc(value: Instant?) = apply { this.estimatedArrivalUtc = value }

        fun withDisruptions(value: List<Disruption>) = apply { this.disruptions = value }

        fun build(): JourneyOption =
            JourneyOption(
                route = route,
                direction = Direction(id = DirectionId(directionId), name = directionName),
                runRef = RunRef(runRef),
                scheduledDepartureUtc = scheduledDepartureUtc,
                estimatedDepartureUtc = estimatedDepartureUtc,
                departurePlatform = departurePlatform?.let(::PlatformNumber),
                scheduledArrivalUtc = scheduledArrivalUtc,
                estimatedArrivalUtc = estimatedArrivalUtc,
                disruptions = disruptions,
            )
    }
}
