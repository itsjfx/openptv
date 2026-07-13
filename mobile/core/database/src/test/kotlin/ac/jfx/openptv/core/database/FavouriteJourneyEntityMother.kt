package ac.jfx.openptv.core.database

import ac.jfx.openptv.core.database.entity.FavouriteJourneyEntity
import ac.jfx.openptv.core.model.RouteType

/**
 * Object Mother for [FavouriteJourneyEntity] (issue #209). Default fixture is the Richmond →
 * Flinders Street train journey — the corridor the journey planner suite standardises on, so
 * failures line up visually with `JourneyOptionMother` and `:core:testing`'s
 * `FavouriteJourneyMother`.
 *
 * Spec lives at `~/.claude/skills/object-mother/skill.md`.
 */
class FavouriteJourneyEntityMother private constructor() {
    companion object {
        private const val DEFAULT_ORIGIN_STOP_ID = 1162
        private const val DEFAULT_ORIGIN_STOP_NAME = "Richmond Station"
        private const val DEFAULT_ORIGIN_STOP_SUBURB = "Richmond"
        private const val DEFAULT_ORIGIN_LAT = -37.8239
        private const val DEFAULT_ORIGIN_LNG = 144.9900
        private const val DEFAULT_DESTINATION_STOP_ID = 1071
        private const val DEFAULT_DESTINATION_STOP_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_DESTINATION_STOP_SUBURB = "Melbourne City"
        private const val DEFAULT_DESTINATION_LAT = -37.8183
        private const val DEFAULT_DESTINATION_LNG = 144.9671
        private const val DEFAULT_ADDED_AT = 1_700_000_000_000L

        fun aFavouriteJourneyEntity(): FavouriteJourneyEntityBuilder = FavouriteJourneyEntityBuilder()
    }

    class FavouriteJourneyEntityBuilder {
        private var originStopId: Int = DEFAULT_ORIGIN_STOP_ID
        private var originStopName: String = DEFAULT_ORIGIN_STOP_NAME
        private var originStopSuburb: String = DEFAULT_ORIGIN_STOP_SUBURB
        private var originRouteType: RouteType = RouteType.Train
        private var originLat: Double = DEFAULT_ORIGIN_LAT
        private var originLng: Double = DEFAULT_ORIGIN_LNG
        private var destinationStopId: Int = DEFAULT_DESTINATION_STOP_ID
        private var destinationStopName: String = DEFAULT_DESTINATION_STOP_NAME
        private var destinationStopSuburb: String = DEFAULT_DESTINATION_STOP_SUBURB
        private var destinationRouteType: RouteType = RouteType.Train
        private var destinationLat: Double = DEFAULT_DESTINATION_LAT
        private var destinationLng: Double = DEFAULT_DESTINATION_LNG
        private var addedAt: Long = DEFAULT_ADDED_AT

        fun withOriginStopId(originStopId: Int) = apply { this.originStopId = originStopId }

        fun withOriginStopName(originStopName: String) = apply { this.originStopName = originStopName }

        fun withOriginStopSuburb(originStopSuburb: String) = apply { this.originStopSuburb = originStopSuburb }

        fun withOriginRouteType(originRouteType: RouteType) = apply { this.originRouteType = originRouteType }

        fun withOriginLat(originLat: Double) = apply { this.originLat = originLat }

        fun withOriginLng(originLng: Double) = apply { this.originLng = originLng }

        fun withDestinationStopId(destinationStopId: Int) = apply { this.destinationStopId = destinationStopId }

        fun withDestinationStopName(destinationStopName: String) =
            apply { this.destinationStopName = destinationStopName }

        fun withDestinationStopSuburb(destinationStopSuburb: String) =
            apply { this.destinationStopSuburb = destinationStopSuburb }

        fun withDestinationRouteType(destinationRouteType: RouteType) =
            apply { this.destinationRouteType = destinationRouteType }

        fun withDestinationLat(destinationLat: Double) = apply { this.destinationLat = destinationLat }

        fun withDestinationLng(destinationLng: Double) = apply { this.destinationLng = destinationLng }

        fun withAddedAt(addedAt: Long) = apply { this.addedAt = addedAt }

        fun build(): FavouriteJourneyEntity =
            FavouriteJourneyEntity(
                originStopId = originStopId,
                originStopName = originStopName,
                originStopSuburb = originStopSuburb,
                originRouteType = originRouteType,
                originLat = originLat,
                originLng = originLng,
                destinationStopId = destinationStopId,
                destinationStopName = destinationStopName,
                destinationStopSuburb = destinationStopSuburb,
                destinationRouteType = destinationRouteType,
                destinationLat = destinationLat,
                destinationLng = destinationLng,
                addedAt = addedAt,
            )
    }
}
