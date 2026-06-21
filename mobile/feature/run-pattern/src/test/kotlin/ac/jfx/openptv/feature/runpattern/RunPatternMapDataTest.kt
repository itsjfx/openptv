package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-function coverage for [RunPatternMapData] — the bounds-fitting maths and the
 * `hasGeometry` graceful-degradation switch (issue #187). Constructed directly (it's a pure type).
 */
class RunPatternMapDataTest {
    @Test
    fun `boundsOf encloses every line and marker point`() {
        val polyline =
            listOf(
                listOf(Coordinates(-37.82, 145.05), Coordinates(-37.80, 145.07)),
            )
        val markers =
            listOf(
                marker(Coordinates(-37.85, 145.02)),
                marker(Coordinates(-37.78, 145.09)),
            )

        val bounds = RunPatternMapData.boundsOf(polyline, markers)!!

        assertThat(bounds.southWest.lat).isWithin(1e-9).of(-37.85)
        assertThat(bounds.southWest.lng).isWithin(1e-9).of(145.02)
        assertThat(bounds.northEast.lat).isWithin(1e-9).of(-37.78)
        assertThat(bounds.northEast.lng).isWithin(1e-9).of(145.09)
    }

    @Test
    fun `boundsOf is null when there are no points`() {
        assertThat(RunPatternMapData.boundsOf(emptyList(), emptyList())).isNull()
    }

    @Test
    fun `hasGeometry is false when nothing to draw`() {
        val data =
            RunPatternMapData(
                routeType = RouteType.Train,
                polyline = listOf(emptyList()),
                markers = emptyList(),
                bounds = null,
            )
        assertThat(data.hasGeometry).isFalse()
    }

    @Test
    fun `hasGeometry is true with markers only`() {
        val data =
            RunPatternMapData(
                routeType = RouteType.Bus,
                polyline = emptyList(),
                markers = listOf(marker(Coordinates(-37.8, 145.0))),
                bounds = null,
            )
        assertThat(data.hasGeometry).isTrue()
    }

    private fun marker(coordinates: Coordinates) =
        RunPatternMapMarker(
            coordinates = coordinates,
            label = "Stop",
            isOrigin = false,
            hasDeparted = false,
        )
}
