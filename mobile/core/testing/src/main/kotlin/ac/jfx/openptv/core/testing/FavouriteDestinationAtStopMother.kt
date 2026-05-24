package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Object Mother for [FavouriteDestinationAtStop] test fixtures. Default fixture is the
 * "North Coburg" destination at Flinders Street — matches the rest of the suite's Flinders
 * default for visual consistency across failures.
 *
 * Mirrors `:core:database`'s `FavouriteDestinationAtStopEntityMother` 1:1 so a test can build
 * either shape and the field values line up. See `~/.claude/skills/object-mother/skill.md`.
 */
class FavouriteDestinationAtStopMother private constructor() {
    companion object {
        private const val DEFAULT_STOP_ID = 1071
        private const val DEFAULT_DESTINATION_KEY = "north coburg"
        private const val DEFAULT_DESTINATION_NAME = "North Coburg"
        private const val DEFAULT_STOP_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_STOP_SUBURB = "Melbourne City"
        private const val DEFAULT_LAT = -37.8183
        private const val DEFAULT_LNG = 144.9671
        private const val DEFAULT_POSITION = 0
        private val DEFAULT_ADDED_AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        fun aFavouriteDestinationAtStop(): FavouriteDestinationAtStopBuilder =
            FavouriteDestinationAtStopBuilder()
    }

    class FavouriteDestinationAtStopBuilder {
        private var stopId: Int = DEFAULT_STOP_ID
        private var destinationKey: String = DEFAULT_DESTINATION_KEY
        private var routeType: RouteType = RouteType.Tram
        private var stopName: String = DEFAULT_STOP_NAME
        private var stopSuburb: String = DEFAULT_STOP_SUBURB
        private var destinationName: String = DEFAULT_DESTINATION_NAME
        private var lat: Double = DEFAULT_LAT
        private var lng: Double = DEFAULT_LNG
        private var position: Int = DEFAULT_POSITION
        private var addedAt: Instant = DEFAULT_ADDED_AT

        fun withStopId(stopId: Int) = apply { this.stopId = stopId }

        fun withDestinationKey(destinationKey: String) = apply { this.destinationKey = destinationKey }

        fun withRouteType(routeType: RouteType) = apply { this.routeType = routeType }

        fun withStopName(stopName: String) = apply { this.stopName = stopName }

        fun withStopSuburb(stopSuburb: String) = apply { this.stopSuburb = stopSuburb }

        fun withDestinationName(destinationName: String) = apply { this.destinationName = destinationName }

        fun withLat(lat: Double) = apply { this.lat = lat }

        fun withLng(lng: Double) = apply { this.lng = lng }

        fun withPosition(position: Int) = apply { this.position = position }

        fun withAddedAt(addedAt: Instant) = apply { this.addedAt = addedAt }

        fun build(): FavouriteDestinationAtStop =
            FavouriteDestinationAtStop(
                stopId = StopId(stopId),
                destinationKey = destinationKey,
                routeType = routeType,
                stopName = stopName,
                stopSuburb = stopSuburb,
                destinationName = destinationName,
                lat = lat,
                lng = lng,
                position = position,
                addedAt = addedAt,
            )
    }
}
