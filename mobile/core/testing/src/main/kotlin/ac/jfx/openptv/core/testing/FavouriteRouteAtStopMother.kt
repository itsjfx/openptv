package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Object Mother for [FavouriteRouteAtStop] test fixtures. Default fixture is route 19 (tram)
 * northbound at Flinders Street — same Flinders fixture the rest of the test suite defaults to
 * for visual consistency across failures.
 *
 * Mirrors `:core:database`'s `FavouriteRouteAtStopEntityMother` 1:1 so a test can build either
 * shape and the field values line up. See `~/.claude/skills/object-mother/skill.md`.
 */
class FavouriteRouteAtStopMother private constructor() {
    companion object {
        private const val DEFAULT_STOP_ID = 1071
        private const val DEFAULT_ROUTE_ID = 1881
        private const val DEFAULT_DIRECTION_ID = 9
        private const val DEFAULT_STOP_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_STOP_SUBURB = "Melbourne City"
        private const val DEFAULT_ROUTE_NUMBER = "19"
        private const val DEFAULT_ROUTE_NAME = "North Coburg - Flinders Street"
        private const val DEFAULT_DIRECTION_NAME = "North Coburg"
        private const val DEFAULT_LAT = -37.8183
        private const val DEFAULT_LNG = 144.9671
        private const val DEFAULT_POSITION = 0
        private val DEFAULT_ADDED_AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        fun aFavouriteRouteAtStop(): FavouriteRouteAtStopBuilder = FavouriteRouteAtStopBuilder()
    }

    @Suppress("TooManyFunctions") // mother-pattern builders necessarily expose one with-* per field
    class FavouriteRouteAtStopBuilder {
        private var stopId: Int = DEFAULT_STOP_ID
        private var routeType: RouteType = RouteType.Tram
        private var routeId: Int = DEFAULT_ROUTE_ID
        private var directionId: Int = DEFAULT_DIRECTION_ID
        private var stopName: String = DEFAULT_STOP_NAME
        private var stopSuburb: String = DEFAULT_STOP_SUBURB
        private var routeNumber: String = DEFAULT_ROUTE_NUMBER
        private var routeName: String = DEFAULT_ROUTE_NAME
        private var directionName: String = DEFAULT_DIRECTION_NAME
        private var lat: Double = DEFAULT_LAT
        private var lng: Double = DEFAULT_LNG
        private var position: Int = DEFAULT_POSITION
        private var addedAt: Instant = DEFAULT_ADDED_AT

        fun withStopId(stopId: Int) = apply { this.stopId = stopId }

        fun withRouteType(routeType: RouteType) = apply { this.routeType = routeType }

        fun withRouteId(routeId: Int) = apply { this.routeId = routeId }

        fun withDirectionId(directionId: Int) = apply { this.directionId = directionId }

        fun withStopName(stopName: String) = apply { this.stopName = stopName }

        fun withStopSuburb(stopSuburb: String) = apply { this.stopSuburb = stopSuburb }

        fun withRouteNumber(routeNumber: String) = apply { this.routeNumber = routeNumber }

        fun withRouteName(routeName: String) = apply { this.routeName = routeName }

        fun withDirectionName(directionName: String) = apply { this.directionName = directionName }

        fun withLat(lat: Double) = apply { this.lat = lat }

        fun withLng(lng: Double) = apply { this.lng = lng }

        fun withPosition(position: Int) = apply { this.position = position }

        fun withAddedAt(addedAt: Instant) = apply { this.addedAt = addedAt }

        fun build(): FavouriteRouteAtStop =
            FavouriteRouteAtStop(
                stopId = StopId(stopId),
                routeType = routeType,
                routeId = RouteId(routeId),
                directionId = DirectionId(directionId),
                stopName = stopName,
                stopSuburb = stopSuburb,
                routeNumber = routeNumber,
                routeName = routeName,
                directionName = directionName,
                lat = lat,
                lng = lng,
                position = position,
                addedAt = addedAt,
            )
    }
}
