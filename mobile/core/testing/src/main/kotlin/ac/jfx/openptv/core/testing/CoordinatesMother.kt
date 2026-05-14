package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.Coordinates

/**
 * Object Mother for [Coordinates] test fixtures. Use the companion factories (`aCoordinates`,
 * `flindersStreet`, `federationSquare`) and chain `with*` methods to override the parts each
 * test cares about. The defaults match `StopMother`'s Flinders Street so cross-fixture identity
 * tests don't need to remember literal lat/lng values.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
class CoordinatesMother private constructor() {
    companion object {
        private const val FLINDERS_LAT = -37.8183
        private const val FLINDERS_LNG = 144.9671
        private const val FEDERATION_LAT = -37.8180
        private const val FEDERATION_LNG = 144.9690

        fun aCoordinates(): CoordinatesBuilder = CoordinatesBuilder()

        fun flindersStreet(): CoordinatesBuilder =
            CoordinatesBuilder().withLat(FLINDERS_LAT).withLng(FLINDERS_LNG)

        fun federationSquare(): CoordinatesBuilder =
            CoordinatesBuilder().withLat(FEDERATION_LAT).withLng(FEDERATION_LNG)
    }

    class CoordinatesBuilder {
        private var lat: Double = FLINDERS_LAT
        private var lng: Double = FLINDERS_LNG

        fun withLat(lat: Double) = apply { this.lat = lat }

        fun withLng(lng: Double) = apply { this.lng = lng }

        fun build(): Coordinates = Coordinates(lat = lat, lng = lng)
    }
}
