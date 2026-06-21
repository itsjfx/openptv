package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteShape
import ac.jfx.openptv.core.model.StopId

/**
 * Object Mother for [RouteShape] (issue #187). Defaults model a tiny two-point line in direction 1
 * plus a single stop coordinate, enough to exercise the run-pattern geopath join. Override via the
 * builder for empty-geopath (graceful-degradation) and multi-direction cases.
 */
class RouteShapeMother private constructor() {
    companion object {
        fun aRouteShape(): RouteShapeBuilder = RouteShapeBuilder()

        /** A shape PTV returned with no geopath at all — drives the "map degrades gracefully" path. */
        fun anEmptyRouteShape(): RouteShapeBuilder =
            RouteShapeBuilder().withGeopath(emptyMap()).withStopCoordinates(emptyMap())
    }

    class RouteShapeBuilder {
        private var geopathByDirection: Map<Int, List<List<Coordinates>>> =
            mapOf(
                DEFAULT_DIRECTION_ID to
                    listOf(
                        listOf(
                            Coordinates(lat = -37.8267, lng = 145.0582),
                            Coordinates(lat = -37.8275, lng = 145.0601),
                        ),
                    ),
            )
        private var stopCoordinates: Map<StopId, Coordinates> =
            mapOf(StopId(DEFAULT_STOP_ID) to Coordinates(lat = -37.8694878, lng = 144.993515))

        fun withGeopath(geopath: Map<Int, List<List<Coordinates>>>) =
            apply { this.geopathByDirection = geopath }

        fun withStopCoordinates(coordinates: Map<StopId, Coordinates>) =
            apply { this.stopCoordinates = coordinates }

        fun build(): RouteShape =
            RouteShape(
                geopathByDirection = geopathByDirection,
                stopCoordinates = stopCoordinates,
            )

        private companion object {
            private const val DEFAULT_DIRECTION_ID = 1
            private const val DEFAULT_STOP_ID = 1013
        }
    }
}
