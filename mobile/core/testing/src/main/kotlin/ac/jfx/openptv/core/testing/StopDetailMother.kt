package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopDetail

/**
 * Object Mother for [StopDetail] test fixtures. Default fixture is Flinders Street with the
 * Mernda line serving it — same stop the rest of the suite defaults to.
 *
 * `aTramStopDetail()` swaps both the stop and the route to route_type=Tram for tests that need
 * to assert mode-specific rendering.
 *
 * See `~/.claude/skills/object-mother/skill.md`.
 */
class StopDetailMother private constructor() {
    companion object {
        fun aStopDetail(): StopDetailBuilder = StopDetailBuilder()

        fun aTramStopDetail(): StopDetailBuilder =
            StopDetailBuilder()
                .withStop(StopMother.aTramStop().build())
                .withServingRoutes(
                    listOf(
                        RouteMother.aRoute()
                            .withRouteType(RouteType.Tram)
                            .withNumber("19")
                            .withName("North Coburg")
                            .build(),
                    ),
                )
    }

    class StopDetailBuilder {
        private var stop: Stop = StopMother.aStop().build()
        private var servingRoutes: List<Route> = listOf(RouteMother.aRoute().build())

        fun withStop(value: Stop) = apply { this.stop = value }

        fun withServingRoutes(value: List<Route>) = apply { this.servingRoutes = value }

        fun build(): StopDetail = StopDetail(stop = stop, servingRoutes = servingRoutes)
    }
}

/**
 * Object Mother for [Route] test fixtures — used by [StopDetailMother] but also useful on its
 * own when a test wants a specific route projection (e.g. the route-chip strip on the screen).
 */
class RouteMother private constructor() {
    companion object {
        private const val DEFAULT_ID = 19
        private const val DEFAULT_NUMBER = ""
        private const val DEFAULT_NAME = "Mernda"

        fun aRoute(): RouteBuilder = RouteBuilder()
    }

    class RouteBuilder {
        private var id: Int = DEFAULT_ID
        private var number: String = DEFAULT_NUMBER
        private var name: String = DEFAULT_NAME
        private var routeType: RouteType = RouteType.Train

        fun withId(id: Int) = apply { this.id = id }

        fun withNumber(value: String) = apply { this.number = value }

        fun withName(value: String) = apply { this.name = value }

        fun withRouteType(value: RouteType) = apply { this.routeType = value }

        fun build(): Route =
            Route(
                id = RouteId(id),
                number = number,
                name = name,
                routeType = routeType,
            )
    }
}
