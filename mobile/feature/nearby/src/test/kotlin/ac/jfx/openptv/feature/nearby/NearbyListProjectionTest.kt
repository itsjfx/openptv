package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.CoordinatesMother
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [toRows], the screen-local projection from `List<Stop>` + user fix to the sorted
 * `List<NearbyListRow>` that backs the bottom-sheet list (issue #80).
 *
 * Pure helper, so direct invocation per the project's "real objects first" rule. The same
 * `toRows` runs on the rendered list — we don't fork the VM into computing the projection.
 */
class NearbyListProjectionTest {
    @Test
    fun `with a fix sorts rows by ascending haversine distance`() {
        val flinders = CoordinatesMother.flindersStreet().build()
        val nearStop =
            StopMother.aStop()
                .withId(1)
                .withName("Federation Square")
                .withLatitude(NEAR_LAT)
                .withLongitude(NEAR_LNG)
                .build()
        val midStop =
            StopMother.aStop()
                .withId(2)
                .withName("Southern Cross")
                .withLatitude(MID_LAT)
                .withLongitude(MID_LNG)
                .build()
        val farStop =
            StopMother.aStop()
                .withId(3)
                .withName("Coburg")
                .withLatitude(FAR_LAT)
                .withLongitude(FAR_LNG)
                .build()

        // Repository order intentionally not the sorted order so the test proves the sort fired.
        val rows = listOf(farStop, nearStop, midStop).toRows(from = flinders)

        assertThat(rows.map { it.stop.id.value }).containsExactly(1, 2, 3).inOrder()
        // Distances should be monotonically increasing (haversine of >0 m for each).
        assertThat(rows[0].distanceMetres!!).isLessThan(rows[1].distanceMetres!!)
        assertThat(rows[1].distanceMetres!!).isLessThan(rows[2].distanceMetres!!)
    }

    @Test
    fun `without a fix preserves repository order and leaves distance null`() {
        val first = StopMother.aStop().withId(10).build()
        val second = StopMother.aStop().withId(20).build()
        val third = StopMother.aStop().withId(30).build()

        val rows = listOf(first, second, third).toRows(from = null)

        assertThat(rows.map { it.stop.id.value }).containsExactly(10, 20, 30).inOrder()
        assertThat(rows.map { it.distanceMetres }).containsExactly(null, null, null)
    }

    @Test
    fun `empty pin list yields an empty row list with or without a fix`() {
        assertThat(emptyList<ac.jfx.openptv.core.model.Stop>().toRows(from = null)).isEmpty()
        assertThat(
            emptyList<ac.jfx.openptv.core.model.Stop>().toRows(
                from = CoordinatesMother.flindersStreet().build(),
            ),
        ).isEmpty()
    }

    @Test
    fun `rows preserve all source stop fields so the row tap can dispatch through onPinClicked`() {
        val source =
            StopMother.aStop()
                .withId(1071)
                .withName("Flinders Street")
                .withSuburb("Melbourne City")
                .withRouteType(RouteType.Train)
                .withLatitude(FLINDERS_LAT)
                .withLongitude(FLINDERS_LNG)
                .build()

        val rows = listOf(source).toRows(from = CoordinatesMother.flindersStreet().build())

        // The row's `stop` field IS the source — same instance — so a row tap and a map-pin tap
        // hand the same `Stop` to `onPinClicked`. This is what guarantees "tap a row to pull up
        // the stop route, the same way tapping a map icon does" (issue #80).
        assertThat(rows.single().stop).isSameInstanceAs(source)
    }

    @Test
    fun `with a fix at the same location distance is zero`() {
        val flinders = CoordinatesMother.flindersStreet().build()
        val flindersStop =
            StopMother.aStop()
                .withLatitude(flinders.lat)
                .withLongitude(flinders.lng)
                .build()

        val rows = listOf(flindersStop).toRows(from = flinders)

        assertThat(rows.single().distanceMetres).isWithin(METRES_TOLERANCE).of(0.0)
    }

    private companion object {
        // Federation Square — ~170 m east of Flinders Street, the same fixture pair used in
        // CoordinatesTest. Picking real Melbourne lat/lngs over synthetic +0.01-degree offsets
        // means a regression in `Coordinates.distanceTo` shows here as well as in the
        // CoordinatesTest.
        private const val NEAR_LAT = -37.8180
        private const val NEAR_LNG = 144.9690

        // Southern Cross — ~700 m WNW of Flinders Street.
        private const val MID_LAT = -37.8183
        private const val MID_LNG = 144.9525

        // Coburg — ~7.8 km north.
        private const val FAR_LAT = -37.7459
        private const val FAR_LNG = 144.9650

        private const val FLINDERS_LAT = -37.8183
        private const val FLINDERS_LNG = 144.9671

        // Same metre-tolerance the CoordinatesTest uses for its near-distance assertions.
        private const val METRES_TOLERANCE = 1.0

        // Suppress unused-private warning for now — the constant lives in case of future tests.
        @Suppress("unused")
        private val UNUSED: Coordinates? = null
    }
}
