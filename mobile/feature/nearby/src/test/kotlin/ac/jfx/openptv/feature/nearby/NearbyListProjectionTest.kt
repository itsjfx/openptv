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

    // -------------------- filter applies to the list (issue #80, bug #3 on PR #84) --------
    //
    // The list rows are derived from the same `filteredBy(routeTypeFilter)` projection the map
    // pins use (single source of truth). When the user toggles a chip off, the list should
    // shrink in lock-step with the map.

    @Test
    fun `filteredBy keeps only stops whose route type is in the filter`() {
        val tramStop = StopMother.aStop().withId(1).withRouteType(RouteType.Tram).build()
        val busStop = StopMother.aStop().withId(2).withRouteType(RouteType.Bus).build()
        val trainStop = StopMother.aStop().withId(3).withRouteType(RouteType.Train).build()

        val pins = listOf(tramStop, busStop, trainStop)
        val rowsTramOnly = pins.filteredBy(setOf(RouteType.Tram)).toRows(from = null)
        val rowsTramAndBus = pins.filteredBy(setOf(RouteType.Tram, RouteType.Bus)).toRows(from = null)

        assertThat(rowsTramOnly.map { it.stop.id.value }).containsExactly(1)
        assertThat(rowsTramAndBus.map { it.stop.id.value }).containsExactly(1, 2)
    }

    @Test
    fun `list shrinks when a chip is toggled off — fewer rows for fewer modes`() {
        val tram = StopMother.aStop().withId(10).withRouteType(RouteType.Tram).build()
        val bus = StopMother.aStop().withId(20).withRouteType(RouteType.Bus).build()
        val train = StopMother.aStop().withId(30).withRouteType(RouteType.Train).build()
        val nightBus = StopMother.aStop().withId(40).withRouteType(RouteType.NightBus).build()
        val pins = listOf(tram, bus, train, nightBus)

        // All four modes selected → all four rows render.
        val all = pins.filteredBy(setOf(RouteType.Tram, RouteType.Bus, RouteType.Train, RouteType.NightBus)).toRows(from = null)
        // User taps NightBus off → list drops to three rows.
        val withoutNightBus = pins.filteredBy(setOf(RouteType.Tram, RouteType.Bus, RouteType.Train)).toRows(from = null)

        assertThat(all).hasSize(4)
        assertThat(withoutNightBus).hasSize(3)
        assertThat(withoutNightBus.map { it.stop.id.value }).doesNotContain(40)
    }

    // -------------------- composite-key regression (bug #6 on PR #84) -----------------------
    //
    // `/stops/location` returns the same `stop_id` once per route type the stop serves — e.g.
    // Box Hill (stop_id 4407) appears as one Train row and one Bus row in the same payload.
    // Both are legitimately distinct (stop, mode) rows the user expects to see, so we don't
    // dedupe; the LazyColumn keys by the (stopId, routeType) pair instead.

    @Test
    fun `duplicate stop ids with different route types both produce a row`() {
        // Box Hill — actual reproduction of the FATAL crash on the AOSP emulator.
        val boxHillTrain =
            StopMother.aStop()
                .withId(BOX_HILL_STOP_ID)
                .withName("Box Hill Railway Station")
                .withRouteType(RouteType.Train)
                .build()
        val boxHillBus =
            StopMother.aStop()
                .withId(BOX_HILL_STOP_ID)
                .withName("Box Hill Railway Station")
                .withRouteType(RouteType.Bus)
                .build()

        val rows = listOf(boxHillTrain, boxHillBus).toRows(from = null)

        // Both rows present — the (id, mode) pair distinguishes them. No dedupe.
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.stop.routeType }).containsExactly(RouteType.Train, RouteType.Bus)
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

        /**
         * Box Hill stop_id — actual repro of the FATAL crash on PR #84. The PTV
         * `/stops/location` endpoint returns this stop once with `route_type=2` (Bus) and once
         * with `route_type=3` (Train) when a query covers the Box Hill interchange.
         */
        private const val BOX_HILL_STOP_ID = 4407

        // Suppress unused-private warning for now — the constant lives in case of future tests.
        @Suppress("unused")
        private val UNUSED: Coordinates? = null
    }
}
