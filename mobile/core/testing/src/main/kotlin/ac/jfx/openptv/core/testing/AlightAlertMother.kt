package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.AlightAlert
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.StopId

/**
 * Object Mother for [AlightAlert] (issue #201). Defaults arm the alert on [RunPatternMother]'s
 * final stop (Flinders Street, id 1071) with its real-world coordinates, both stages unfired —
 * i.e. a freshly armed alert on the Mother's three-stop Lilydale run.
 */
class AlightAlertMother private constructor() {
    companion object {
        fun anAlightAlert(): AlightAlertBuilder = AlightAlertBuilder()
    }

    class AlightAlertBuilder {
        private var stopId: Int = DEFAULT_STOP_ID
        private var stopName: String = DEFAULT_STOP_NAME
        private var coordinates: Coordinates? = DEFAULT_COORDINATES
        private var approachFired: Boolean = false
        private var arrivalFired: Boolean = false

        fun withStopId(value: Int) = apply { this.stopId = value }

        fun withStopName(value: String) = apply { this.stopName = value }

        fun withCoordinates(value: Coordinates?) = apply { this.coordinates = value }

        fun withApproachFired(value: Boolean) = apply { this.approachFired = value }

        fun withArrivalFired(value: Boolean) = apply { this.arrivalFired = value }

        fun build(): AlightAlert =
            AlightAlert(
                stopId = StopId(stopId),
                stopName = stopName,
                coordinates = coordinates,
                approachFired = approachFired,
                arrivalFired = arrivalFired,
            )

        private companion object {
            /** RunPatternMother's third (terminus) stop. */
            private const val DEFAULT_STOP_ID = 1071
            private const val DEFAULT_STOP_NAME = "Flinders Street Railway Station"
            private val DEFAULT_COORDINATES = Coordinates(lat = -37.8183, lng = 144.9671)
        }
    }
}
