package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.Disruption
import ac.jfx.openptv.core.model.PlatformNumber
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RunRef
import kotlinx.datetime.Instant

/**
 * Object Mother for [Departure] test fixtures. Use the companion factories (`aDeparture`,
 * `aDelayedDeparture`, `aDepartureWithoutEstimate`) and chain `with*` methods to override only
 * the fields each test cares about. Calling `.build()` with no overrides yields a Mernda-line
 * train departing Flinders Street at `2026-05-14T09:00:00Z`, real-time estimate +90 s, platform
 * `2`, direction `City`.
 *
 * Default to the same stable fixture across the codebase so failures are visually consistent —
 * mirrors [StopMother] and the NIA convention. See `~/.claude/skills/object-mother/skill.md`.
 */
class DepartureMother private constructor() {
    companion object {
        private const val DEFAULT_ROUTE_ID = 1
        private const val DEFAULT_DIRECTION_ID = 1
        private const val DEFAULT_DIRECTION_NAME = "City"
        private const val DEFAULT_RUN_REF = "OPS-9999"
        private val DEFAULT_SCHEDULED: Instant = Instant.parse("2026-05-14T09:00:00Z")
        private val DEFAULT_ESTIMATED: Instant = Instant.parse("2026-05-14T09:01:30Z")
        private const val DEFAULT_PLATFORM = "2"

        fun aDeparture(): DepartureBuilder = DepartureBuilder()

        /** A real-time prediction is missing — the formatter falls back to "scheduled". */
        fun aDepartureWithoutEstimate(): DepartureBuilder = DepartureBuilder().withEstimatedDepartureUtc(null)

        /** A departure carrying a disruption (the screen surfaces a warning indicator). */
        fun aDisruptedDeparture(): DepartureBuilder =
            DepartureBuilder().withDisruptions(listOf(DisruptionMother.aDisruption().build()))
    }

    class DepartureBuilder {
        private var routeId: Int = DEFAULT_ROUTE_ID
        private var directionId: Int = DEFAULT_DIRECTION_ID
        private var directionName: String = DEFAULT_DIRECTION_NAME
        private var runRef: String = DEFAULT_RUN_REF
        private var scheduledDepartureUtc: Instant = DEFAULT_SCHEDULED
        private var estimatedDepartureUtc: Instant? = DEFAULT_ESTIMATED
        private var platform: String? = DEFAULT_PLATFORM
        private var disruptions: List<Disruption> = emptyList()

        fun withRouteId(id: Int) = apply { this.routeId = id }

        fun withDirectionId(id: Int) = apply { this.directionId = id }

        fun withDirectionName(name: String) = apply { this.directionName = name }

        fun withRunRef(ref: String) = apply { this.runRef = ref }

        fun withScheduledDepartureUtc(value: Instant) = apply { this.scheduledDepartureUtc = value }

        fun withEstimatedDepartureUtc(value: Instant?) = apply { this.estimatedDepartureUtc = value }

        fun withPlatform(value: String?) = apply { this.platform = value }

        fun withDisruptions(value: List<Disruption>) = apply { this.disruptions = value }

        fun build(): Departure =
            Departure(
                routeId = RouteId(routeId),
                runRef = RunRef(runRef),
                scheduledDepartureUtc = scheduledDepartureUtc,
                estimatedDepartureUtc = estimatedDepartureUtc,
                platform = platform?.let(::PlatformNumber),
                direction = Direction(id = DirectionId(directionId), name = directionName),
                disruptions = disruptions,
            )
    }
}
