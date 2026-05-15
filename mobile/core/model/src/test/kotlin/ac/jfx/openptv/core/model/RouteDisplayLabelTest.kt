package ac.jfx.openptv.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Per-`route_type` rule from issue #88: trams / buses / night buses show the public
 * `route_number` (the digits painted on the side of the vehicle), trains and V/Line services
 * show the line `route_name` ("Lilydale", "Sandringham") because PTV returns `route_number`
 * empty on those. The blank-on-both fallback exists so a route with no metadata can still be
 * told apart from its neighbour in a multi-route header.
 */
class RouteDisplayLabelTest {
    @Test
    fun `tram prefers route number`() {
        val label = labelFor(RouteType.Tram, number = "96", name = "East Brunswick")
        assertThat(label).isEqualTo("96")
    }

    @Test
    fun `bus prefers route number`() {
        val label = labelFor(RouteType.Bus, number = "612", name = "Box Hill - Chadstone")
        assertThat(label).isEqualTo("612")
    }

    @Test
    fun `night bus prefers route number`() {
        val label = labelFor(RouteType.NightBus, number = "942", name = "Caroline Springs Night")
        assertThat(label).isEqualTo("942")
    }

    @Test
    fun `train prefers route name`() {
        // PTV returns an empty `route_number` on trains; even when it's present the line name
        // is what the public recognises.
        val label = labelFor(RouteType.Train, number = "", name = "Lilydale")
        assertThat(label).isEqualTo("Lilydale")
    }

    @Test
    fun `vline prefers route name`() {
        val label = labelFor(RouteType.VLine, number = "", name = "Bendigo")
        assertThat(label).isEqualTo("Bendigo")
    }

    @Test
    fun `tram falls back to name when number is blank`() {
        val label = labelFor(RouteType.Tram, number = "", name = "Free Tram Zone")
        assertThat(label).isEqualTo("Free Tram Zone")
    }

    @Test
    fun `train falls back to number when name is blank`() {
        val label = labelFor(RouteType.Train, number = "X1", name = "")
        assertThat(label).isEqualTo("X1")
    }

    @Test
    fun `blank on both fields falls back to route id`() {
        val label = labelFor(RouteType.Tram, number = "", name = "", id = 12345)
        assertThat(label).isEqualTo("#12345")
    }

    @Test
    fun `unknown route type behaves like bus`() {
        // No reason for `Unknown` to crash a render — it should still pick the number first
        // and fall back gracefully, same as the AOSP rendering pipeline does for an unknown mode
        // icon.
        val label = labelFor(RouteType.Unknown, number = "999", name = "Mystery")
        assertThat(label).isEqualTo("999")
    }

    @Test
    fun `Route displayLabel delegates to the shared helper`() {
        // The instance property on [Route] should produce the same string as the standalone
        // helper — guards against the two drifting apart if either is touched in isolation.
        val route =
            Route(
                id = RouteId(42),
                number = "",
                name = "Belgrave",
                routeType = RouteType.Train,
            )
        assertThat(route.displayLabel).isEqualTo("Belgrave")
    }

    private fun labelFor(
        routeType: RouteType,
        number: String,
        name: String,
        id: Int = 7,
    ): String = routeDisplayLabel(routeType, number, name, RouteId(id))
}
