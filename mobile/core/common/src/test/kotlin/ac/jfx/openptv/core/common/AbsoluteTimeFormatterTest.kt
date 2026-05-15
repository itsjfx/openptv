package ac.jfx.openptv.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test

/**
 * Exercises every branch of [AbsoluteTimeFormatter]. The formatter is a pure object so we use it
 * directly (Object Mothers aren't needed for stateless services — see CLAUDE.md's testing rules).
 *
 * The test pins the time zone to UTC so the input [Instant] and the rendered hour/minute pair
 * line up without timezone arithmetic — the formatter's behaviour around `TimeZone` is delegated
 * to `kotlinx.datetime.toLocalDateTime` and isn't this formatter's contract to retest.
 */
class AbsoluteTimeFormatterTest {
    private val utc = TimeZone.UTC

    @Test
    fun `15-30 UTC renders as 15-30 in 24-hour mode`() {
        val instant = Instant.parse("2026-05-14T15:30:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = true, timeZone = utc)
        assertThat(result).isEqualTo("15:30")
    }

    @Test
    fun `15-30 UTC renders as 3-30 PM in 12-hour mode`() {
        val instant = Instant.parse("2026-05-14T15:30:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).isEqualTo("3:30 PM")
    }

    @Test
    fun `midnight 00-00 renders as 12-00 AM in 12-hour mode`() {
        // Midnight is the canonical "off-by-one" trap — `00 mod 12` is `0` and would render as
        // "0:00 AM" without the adjustment in the formatter. Pin the behaviour.
        val instant = Instant.parse("2026-05-14T00:00:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).isEqualTo("12:00 AM")
    }

    @Test
    fun `noon 12-00 renders as 12-00 PM in 12-hour mode`() {
        val instant = Instant.parse("2026-05-14T12:00:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).isEqualTo("12:00 PM")
    }

    @Test
    fun `1am renders as 1-00 AM in 12-hour mode`() {
        val instant = Instant.parse("2026-05-14T01:00:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).isEqualTo("1:00 AM")
    }

    @Test
    fun `11-59pm renders as 11-59 PM in 12-hour mode`() {
        val instant = Instant.parse("2026-05-14T23:59:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).isEqualTo("11:59 PM")
    }

    @Test
    fun `24-hour mode preserves leading zero on hour`() {
        // The 24-hour format always pads to two digits so the column never jitters between
        // rows — "09:05" vs "9:05" would shift the row's right-aligned text on the favourite
        // screen. Pin the contract.
        val instant = Instant.parse("2026-05-14T09:05:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = true, timeZone = utc)
        assertThat(result).isEqualTo("09:05")
    }

    @Test
    fun `12-hour mode does not pad hour with leading zero`() {
        // Standard wall-clock convention is single-digit hours in the 12-hour face. Pin the
        // shape so a refactor doesn't accidentally pad.
        val instant = Instant.parse("2026-05-14T09:05:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).isEqualTo("9:05 AM")
    }

    @Test
    fun `minutes are always two digits in 12-hour mode`() {
        val instant = Instant.parse("2026-05-14T09:05:00Z")
        val result = AbsoluteTimeFormatter.format(instant, use24Hour = false, timeZone = utc)
        assertThat(result).contains(":05")
    }
}
