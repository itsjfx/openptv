package ac.jfx.openptv.core.network.model

/**
 * Object Mother for the internal [DepartureDto]. Lives in `:core:network/src/test/model/` next
 * to the DTO it builds — [DepartureDto] is `internal`, so only same-module code can construct
 * it. The mapper tests in `:core:network` are the only consumer.
 *
 * Defaults to a Train (route_type 0) departure on the canonical Flinders-Street stop with
 * scheduled time `2026-05-14T09:00:00Z` and a 90 s positive delay on the estimate — picked so
 * test failure dumps are visually identifiable.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
internal class DepartureDtoMother private constructor() {
    companion object {
        private const val DEFAULT_ROUTE_ID = 1
        private const val DEFAULT_DIRECTION_ID = 1
        private const val DEFAULT_RUN_REF = "OPS-9999"
        private const val DEFAULT_SCHEDULED_UTC = "2026-05-14T09:00:00Z"
        private const val DEFAULT_ESTIMATED_UTC = "2026-05-14T09:01:30Z"
        private const val DEFAULT_PLATFORM = "2"

        internal fun aDepartureDto(): DepartureDtoBuilder = DepartureDtoBuilder()
    }

    internal class DepartureDtoBuilder {
        private var routeId: Int = DEFAULT_ROUTE_ID
        private var directionId: Int = DEFAULT_DIRECTION_ID
        private var runRef: String = DEFAULT_RUN_REF
        private var scheduledDepartureUtc: String = DEFAULT_SCHEDULED_UTC
        private var estimatedDepartureUtc: String? = DEFAULT_ESTIMATED_UTC
        private var platformNumber: String? = DEFAULT_PLATFORM
        private var disruptionIds: List<Long> = emptyList()

        fun withRouteId(id: Int) = apply { this.routeId = id }

        fun withDirectionId(id: Int) = apply { this.directionId = id }

        fun withRunRef(ref: String) = apply { this.runRef = ref }

        fun withScheduledDepartureUtc(value: String) = apply { this.scheduledDepartureUtc = value }

        fun withEstimatedDepartureUtc(value: String?) = apply { this.estimatedDepartureUtc = value }

        fun withPlatformNumber(value: String?) = apply { this.platformNumber = value }

        fun withDisruptionIds(ids: List<Long>) = apply { this.disruptionIds = ids }

        fun build(): DepartureDto =
            DepartureDto(
                routeId = routeId,
                directionId = directionId,
                runRef = runRef,
                scheduledDepartureUtc = scheduledDepartureUtc,
                estimatedDepartureUtc = estimatedDepartureUtc,
                platformNumber = platformNumber,
                disruptionIds = disruptionIds,
            )
    }
}
