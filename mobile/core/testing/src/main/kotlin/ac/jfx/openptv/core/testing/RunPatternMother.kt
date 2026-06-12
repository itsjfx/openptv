package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.PlatformNumber
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunPatternStop
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Object Mother for [RunPattern] (issue #132). Defaults model a three-stop Lilydale-line run:
 * one stop already called at, two upcoming. Tests that need different shapes override via the
 * builder, or compose their own stop list from [RunPatternStopMother].
 */
class RunPatternMother private constructor() {
    companion object {
        fun aRunPattern(): RunPatternBuilder = RunPatternBuilder()

        fun aRunPatternWithoutRoute(): RunPatternBuilder = RunPatternBuilder().withRoute(null)
    }

    class RunPatternBuilder {
        private var route: Route? =
            Route(
                id = RouteId(DEFAULT_ROUTE_ID),
                number = "",
                name = DEFAULT_ROUTE_NAME,
                routeType = RouteType.Train,
            )
        private var directionName: String = DEFAULT_DIRECTION_NAME
        private var stops: List<RunPatternStop> =
            listOf(
                RunPatternStopMother.aPastPatternStop().build(),
                RunPatternStopMother.aPatternStop().build(),
                RunPatternStopMother.aPatternStop()
                    .withStopId(DEFAULT_THIRD_STOP_ID)
                    .withStopName("Flinders Street Railway Station")
                    .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:10:00Z"))
                    .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:11:00Z"))
                    .build(),
            )

        fun withRoute(route: Route?) = apply { this.route = route }

        fun withDirectionName(name: String) = apply { this.directionName = name }

        fun withStops(stops: List<RunPatternStop>) = apply { this.stops = stops }

        fun build(): RunPattern =
            RunPattern(
                route = route,
                directionName = directionName,
                stops = stops,
            )

        private companion object {
            private const val DEFAULT_ROUTE_ID = 5
            private const val DEFAULT_ROUTE_NAME = "Lilydale"
            private const val DEFAULT_DIRECTION_NAME = "Flinders Street"
            private const val DEFAULT_THIRD_STOP_ID = 1071
        }
    }
}

/**
 * Object Mother for a single [RunPatternStop]. The default is an upcoming stop with a live
 * estimate; [aPastPatternStop] is a stop the run already called at (no estimate, scheduled in
 * the past relative to the fixed test clock convention of `2026-05-14T09:00:00Z`).
 */
class RunPatternStopMother private constructor() {
    companion object {
        fun aPatternStop(): RunPatternStopBuilder = RunPatternStopBuilder()

        fun aPastPatternStop(): RunPatternStopBuilder =
            RunPatternStopBuilder()
                .withStopId(PAST_STOP_ID)
                .withStopName("Richmond Station")
                .withScheduledDepartureUtc(Instant.parse("2026-05-14T08:50:00Z"))
                .withEstimatedDepartureUtc(null)

        private const val PAST_STOP_ID = 1162
    }

    class RunPatternStopBuilder {
        private var stopId: Int = DEFAULT_STOP_ID
        private var stopName: String = DEFAULT_STOP_NAME
        private var stopSuburb: String = DEFAULT_STOP_SUBURB
        private var scheduledDepartureUtc: Instant = DEFAULT_SCHEDULED
        private var estimatedDepartureUtc: Instant? = DEFAULT_ESTIMATED
        private var platform: String? = DEFAULT_PLATFORM

        fun withStopId(id: Int) = apply { this.stopId = id }

        fun withStopName(name: String) = apply { this.stopName = name }

        fun withStopSuburb(suburb: String) = apply { this.stopSuburb = suburb }

        fun withScheduledDepartureUtc(value: Instant) = apply { this.scheduledDepartureUtc = value }

        fun withEstimatedDepartureUtc(value: Instant?) = apply { this.estimatedDepartureUtc = value }

        fun withPlatform(value: String?) = apply { this.platform = value }

        fun build(): RunPatternStop =
            RunPatternStop(
                stopId = StopId(stopId),
                stopName = stopName,
                stopSuburb = stopSuburb,
                scheduledDepartureUtc = scheduledDepartureUtc,
                estimatedDepartureUtc = estimatedDepartureUtc,
                platform = platform?.let(::PlatformNumber),
            )

        private companion object {
            private const val DEFAULT_STOP_ID = 1104
            private const val DEFAULT_STOP_NAME = "East Richmond Station"
            private const val DEFAULT_STOP_SUBURB = "Richmond"
            private val DEFAULT_SCHEDULED: Instant = Instant.parse("2026-05-14T09:05:00Z")
            private val DEFAULT_ESTIMATED: Instant = Instant.parse("2026-05-14T09:06:00Z")
            private const val DEFAULT_PLATFORM = "2"
        }
    }
}
