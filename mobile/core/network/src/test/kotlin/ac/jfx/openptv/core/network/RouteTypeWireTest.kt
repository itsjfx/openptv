package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the domain-enum → wire-code map. Pair test against the inverse `RouteType.fromCode` so a
 * future renumbering can't silently break URL composition.
 */
class RouteTypeWireTest {
    @Test
    fun `each documented enum maps to its PTV code and roundtrips`() {
        val cases =
            listOf(
                RouteType.Train to 0,
                RouteType.Tram to 1,
                RouteType.Bus to 2,
                RouteType.VLine to 3,
                RouteType.NightBus to 4,
            )
        cases.forEach { (enum, code) ->
            assertThat(enum.toPtvCode()).isEqualTo(code)
            assertThat(RouteType.fromCode(code)).isEqualTo(enum)
        }
    }

    @Test
    fun `Unknown falls back to Train code so request still composes`() {
        assertThat(RouteType.Unknown.toPtvCode()).isEqualTo(0)
    }
}
