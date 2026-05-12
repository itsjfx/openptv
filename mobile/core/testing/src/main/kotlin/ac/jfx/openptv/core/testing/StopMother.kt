package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId

/**
 * Object Mother for [Stop] test fixtures. Use the companion factories (`aStop`,
 * `aTramStop`, etc.) and chain `with*` methods to override only the fields each test cares
 * about. Calling `.build()` with no overrides yields a valid Flinders Street stop.
 *
 * Default to the same stable Flinders Street fixture across the codebase so failures are
 * visually consistent. See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
class StopMother private constructor() {

    companion object {
        private const val DEFAULT_ID = 1071
        private const val DEFAULT_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_SUBURB = "Melbourne City"
        private const val DEFAULT_LATITUDE = -37.8183
        private const val DEFAULT_LONGITUDE = 144.9671

        fun aStop(): StopBuilder = StopBuilder()
        fun aTramStop(): StopBuilder = StopBuilder().withRouteType(RouteType.Tram)
        fun aBusStop(): StopBuilder = StopBuilder().withRouteType(RouteType.Bus)
    }

    class StopBuilder {
        private var id: Int = DEFAULT_ID
        private var name: String = DEFAULT_NAME
        private var suburb: String = DEFAULT_SUBURB
        private var routeType: RouteType = RouteType.Train
        private var latitude: Double = DEFAULT_LATITUDE
        private var longitude: Double = DEFAULT_LONGITUDE

        fun withId(id: Int) = apply { this.id = id }
        fun withName(name: String) = apply { this.name = name }
        fun withSuburb(suburb: String) = apply { this.suburb = suburb }
        fun withRouteType(routeType: RouteType) = apply { this.routeType = routeType }
        fun withLatitude(latitude: Double) = apply { this.latitude = latitude }
        fun withLongitude(longitude: Double) = apply { this.longitude = longitude }

        fun build(): Stop = Stop(
            id = StopId(id),
            name = name,
            suburb = suburb,
            routeType = routeType,
            latitude = latitude,
            longitude = longitude,
        )
    }
}
