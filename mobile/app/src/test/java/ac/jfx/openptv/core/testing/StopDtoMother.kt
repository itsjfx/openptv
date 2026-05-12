package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.network.StopDto

/**
 * Object Mother for the internal [StopDto]. Tests living in `ac.jfx.openptv.core.network` get
 * to see internal types; this mother sits in the same package so DTO-mapper tests can keep
 * using sensible defaults without inline constructors.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
class StopDtoMother private constructor() {

    companion object {
        private const val DEFAULT_ID = 1071
        private const val DEFAULT_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_SUBURB = "Melbourne City"
        private const val DEFAULT_ROUTE_TYPE = 0
        private const val DEFAULT_LATITUDE = -37.8183
        private const val DEFAULT_LONGITUDE = 144.9671

        internal fun aStopDto(): StopDtoBuilder = StopDtoBuilder()
    }

    internal class StopDtoBuilder {
        private var stopId: Int = DEFAULT_ID
        private var stopName: String = DEFAULT_NAME
        private var stopSuburb: String = DEFAULT_SUBURB
        private var routeType: Int = DEFAULT_ROUTE_TYPE
        private var stopLatitude: Double = DEFAULT_LATITUDE
        private var stopLongitude: Double = DEFAULT_LONGITUDE

        fun withStopId(id: Int) = apply { this.stopId = id }
        fun withStopName(name: String) = apply { this.stopName = name }
        fun withStopSuburb(suburb: String) = apply { this.stopSuburb = suburb }
        fun withRouteType(code: Int) = apply { this.routeType = code }
        fun withStopLatitude(latitude: Double) = apply { this.stopLatitude = latitude }
        fun withStopLongitude(longitude: Double) = apply { this.stopLongitude = longitude }

        fun build(): StopDto = StopDto(
            stopId = stopId,
            stopName = stopName,
            stopSuburb = stopSuburb,
            routeType = routeType,
            stopLatitude = stopLatitude,
            stopLongitude = stopLongitude,
        )
    }
}
