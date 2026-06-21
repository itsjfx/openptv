package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [spreadColocatedStops] — the pure fan-out that stops co-located map pins (e.g. the
 * Richmond train stop and the V/Line "Richmond Railway Station", both `stop_id 1162` at identical
 * lat/lng) from stacking on a single pixel (issue #172).
 */
class ColocationSpreadTest {
    @Test
    fun `a lone stop keeps its exact coordinate`() {
        val stop = StopMother.aStop().withLatitude(RICHMOND_LAT).withLongitude(RICHMOND_LNG).build()

        val result = spreadColocatedStops(listOf(stop))

        assertThat(result).hasSize(1)
        val (resolved, coord) = result.single()
        assertThat(resolved).isEqualTo(stop)
        assertThat(coord).isEqualTo(Coordinates(lat = RICHMOND_LAT, lng = RICHMOND_LNG))
    }

    @Test
    fun `two stops on the same point are fanned apart so each dot is distinct`() {
        val train =
            StopMother.aStop()
                .withId(1162)
                .withRouteType(RouteType.Train)
                .withLatitude(RICHMOND_LAT)
                .withLongitude(RICHMOND_LNG)
                .build()
        val vline =
            StopMother.aStop()
                .withId(1162)
                .withRouteType(RouteType.VLine)
                .withLatitude(RICHMOND_LAT)
                .withLongitude(RICHMOND_LNG)
                .build()

        val byStop = spreadColocatedStops(listOf(train, vline)).toMap()

        val shared = Coordinates(lat = RICHMOND_LAT, lng = RICHMOND_LNG)
        val trainCoord = byStop.getValue(train)
        val vlineCoord = byStop.getValue(vline)

        // Each dot is nudged off the shared point by roughly the configured ~20 m radius...
        assertThat(shared.distanceTo(trainCoord)).isWithin(TOLERANCE_M).of(OFFSET_M)
        assertThat(shared.distanceTo(vlineCoord)).isWithin(TOLERANCE_M).of(OFFSET_M)
        // ...and they end up well separated from each other (≈ two radii apart).
        assertThat(trainCoord.distanceTo(vlineCoord)).isGreaterThan(2 * OFFSET_M - TOLERANCE_M)
    }

    @Test
    fun `fan-out is deterministic across calls and independent of input order`() {
        val train =
            StopMother.aStop().withId(1162).withRouteType(RouteType.Train)
                .withLatitude(RICHMOND_LAT).withLongitude(RICHMOND_LNG).build()
        val vline =
            StopMother.aStop().withId(1162).withRouteType(RouteType.VLine)
                .withLatitude(RICHMOND_LAT).withLongitude(RICHMOND_LNG).build()

        val first = spreadColocatedStops(listOf(train, vline)).toMap()
        val reversed = spreadColocatedStops(listOf(vline, train)).toMap()

        // Slot is keyed by the stop (sorted by route type then id), not its position in the input,
        // so a re-render with a reordered list places each dot identically — no jitter.
        assertThat(reversed.getValue(train)).isEqualTo(first.getValue(train))
        assertThat(reversed.getValue(vline)).isEqualTo(first.getValue(vline))
    }

    @Test
    fun `distinct nearby stops are not merged and stay put`() {
        val a = StopMother.aStop().withId(1).withLatitude(RICHMOND_LAT).withLongitude(RICHMOND_LNG).build()
        // ~150 m north — a genuinely different stop, outside the co-location grid cell.
        val b = StopMother.aStop().withId(2).withLatitude(RICHMOND_LAT + 0.0013).withLongitude(RICHMOND_LNG).build()

        val byStop = spreadColocatedStops(listOf(a, b)).toMap()

        assertThat(byStop.getValue(a)).isEqualTo(Coordinates(lat = RICHMOND_LAT, lng = RICHMOND_LNG))
        assertThat(byStop.getValue(b)).isEqualTo(Coordinates(lat = RICHMOND_LAT + 0.0013, lng = RICHMOND_LNG))
    }

    private companion object {
        // Richmond Railway Station — the real shared point from PTV (train + V/Line, stop_id 1162).
        private const val RICHMOND_LAT = -37.82407
        private const val RICHMOND_LNG = 144.99016

        // The fan-out radius mirrors COLOCATION_OFFSET_METERS in the production code.
        private const val OFFSET_M = 20.0
        private const val TOLERANCE_M = 3.0
    }
}
