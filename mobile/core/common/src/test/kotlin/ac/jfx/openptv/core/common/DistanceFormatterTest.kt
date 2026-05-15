package ac.jfx.openptv.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [DistanceFormatter]. The formatter is pure — direct construction is the right
 * test-double per the project's "real objects first" rule.
 */
class DistanceFormatterTest {
    private val formatter = DistanceFormatter()

    @Test
    fun `metres under 1 km rounds down to nearest 10 m`() {
        assertThat(formatter.format(metres = 87.0)).isEqualTo("80 m")
        assertThat(formatter.format(metres = 50.0)).isEqualTo("50 m")
        assertThat(formatter.format(metres = 999.0)).isEqualTo("990 m")
    }

    @Test
    fun `zero metres formats as 0 m`() {
        assertThat(formatter.format(metres = 0.0)).isEqualTo("0 m")
    }

    @Test
    fun `negative metres clamps to zero`() {
        assertThat(formatter.format(metres = -3.0)).isEqualTo("0 m")
    }

    @Test
    fun `between 1 km and 10 km uses one decimal of km`() {
        assertThat(formatter.format(metres = 1_000.0)).isEqualTo("1.0 km")
        assertThat(formatter.format(metres = 1_234.0)).isEqualTo("1.2 km")
        assertThat(formatter.format(metres = 9_949.0)).isEqualTo("9.9 km")
    }

    @Test
    fun `at or above 10 km uses whole km`() {
        assertThat(formatter.format(metres = 10_000.0)).isEqualTo("10 km")
        assertThat(formatter.format(metres = 12_500.0)).isEqualTo("12 km")
        assertThat(formatter.format(metres = 99_900.0)).isEqualTo("99 km")
    }
}
