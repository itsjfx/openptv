package ac.jfx.openptv.core.network.model

/**
 * Object Mother for the internal [StopDetailsDto] (the inner `stop` block of [StopResponseDto]).
 * Lives in `:core:network/src/test/model/` because [StopDetailsDto] is `internal`. Defaults to
 * the canonical Flinders Street fixture so dumps stay visually consistent across the test
 * suite.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
internal class StopDetailsDtoMother private constructor() {
    companion object {
        private const val DEFAULT_ID = 1071
        private const val DEFAULT_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_SUBURB = "Melbourne City"
        private const val DEFAULT_ROUTE_TYPE = 0
        private const val DEFAULT_LATITUDE = -37.8183
        private const val DEFAULT_LONGITUDE = 144.9671

        internal fun aStopDetailsDto(): StopDetailsDtoBuilder = StopDetailsDtoBuilder()
    }

    internal class StopDetailsDtoBuilder {
        private var stopId: Int = DEFAULT_ID
        private var stopName: String = DEFAULT_NAME
        private var stopSuburb: String = DEFAULT_SUBURB
        private var routeType: Int = DEFAULT_ROUTE_TYPE
        private var stopLatitude: Double = DEFAULT_LATITUDE
        private var stopLongitude: Double = DEFAULT_LONGITUDE
        private var routes: List<RouteDto> = emptyList()

        fun withStopId(id: Int) = apply { this.stopId = id }

        fun withStopName(name: String) = apply { this.stopName = name }

        fun withStopSuburb(suburb: String) = apply { this.stopSuburb = suburb }

        fun withRouteType(code: Int) = apply { this.routeType = code }

        fun withStopLatitude(value: Double) = apply { this.stopLatitude = value }

        fun withStopLongitude(value: Double) = apply { this.stopLongitude = value }

        fun withRoutes(value: List<RouteDto>) = apply { this.routes = value }

        fun build(): StopDetailsDto =
            StopDetailsDto(
                stopId = stopId,
                stopName = stopName,
                stopSuburb = stopSuburb,
                routeType = routeType,
                stopLatitude = stopLatitude,
                stopLongitude = stopLongitude,
                routes = routes,
            )
    }
}
