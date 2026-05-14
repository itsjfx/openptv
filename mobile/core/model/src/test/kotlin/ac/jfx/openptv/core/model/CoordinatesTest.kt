package ac.jfx.openptv.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Smoke coverage for [Coordinates.distanceTo]. We don't aim to validate the haversine formula
 * end-to-end — that would just be re-implementing the math. Instead we pin a handful of
 * well-known Melbourne pairs against reference distances. Tolerance is 1% (haversine vs.
 * the "real" Vincenty distance is < 0.5% at PTV latitudes).
 */
class CoordinatesTest {
    @Test
    fun `distanceTo same point is zero`() {
        val p = Coordinates(lat = -37.8183, lng = 144.9671)
        assertThat(p.distanceTo(p)).isEqualTo(0.0)
    }

    @Test
    fun `distanceTo is symmetric`() {
        val flinders = Coordinates(lat = -37.8183, lng = 144.9671)
        val fed = Coordinates(lat = -37.8180, lng = 144.9690)
        val ab = flinders.distanceTo(fed)
        val ba = fed.distanceTo(flinders)
        // Floating-point round-trip: equal to ~1 µm. Truth's `isWithin` makes the intent explicit.
        assertThat(ab).isWithin(0.001).of(ba)
    }

    @Test
    fun `Flinders to Federation Square is roughly 170m`() {
        val flinders = Coordinates(lat = -37.8183, lng = 144.9671)
        val fed = Coordinates(lat = -37.8180, lng = 144.9690)
        // Reference distance ~170 m via Google Maps' "measure distance". 5 m tolerance covers
        // both haversine error and the rounded lat/lng above.
        assertThat(flinders.distanceTo(fed)).isWithin(METRES_TOLERANCE_NEAR).of(170.0)
    }

    @Test
    fun `Melbourne CBD to Coburg is roughly 8km`() {
        val cbd = Coordinates(lat = -37.8136, lng = 144.9631)
        val coburg = Coordinates(lat = -37.7430, lng = 144.9650)
        // Reference distance ~7.85 km via Google Maps. 100 m tolerance keeps the assertion stable
        // across rounding without losing the order-of-magnitude check.
        assertThat(cbd.distanceTo(coburg)).isWithin(METRES_TOLERANCE_FAR).of(7_850.0)
    }

    @Test
    fun `distanceTo grows monotonically with displacement`() {
        val anchor = Coordinates(lat = -37.8136, lng = 144.9631)
        val near = Coordinates(lat = -37.8140, lng = 144.9631)
        val far = Coordinates(lat = -37.8500, lng = 144.9631)
        assertThat(anchor.distanceTo(near)).isLessThan(anchor.distanceTo(far))
    }

    private companion object {
        // ~3 m — typical haversine vs. geodesic discrepancy at street scale.
        private const val METRES_TOLERANCE_NEAR: Double = 5.0

        // ~100 m — keeps the order-of-magnitude assertion stable across rounding.
        private const val METRES_TOLERANCE_FAR: Double = 100.0
    }
}
