package ac.jfx.openptv.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises every branch of [RelativeTimeFormatter]. The clock is pinned to a single fixed
 * [Instant] (`NOW`) so every assertion is deterministic — no `Clock.System`, no real time.
 *
 * The formatter is a pure type, so we construct it directly (Object Mothers aren't needed for
 * stateless services). See `docs/mobile/00-conventions.md` and the testing section in CLAUDE.md.
 */
class RelativeTimeFormatterTest {
    private val now: Instant = Instant.parse("2026-05-14T08:00:00Z")
    private val formatter = RelativeTimeFormatter(FixedClock(now))

    @Test
    fun `target exactly at now returns now label`() {
        val result = formatter.format(scheduled = now, estimated = now)
        assertThat(result).isEqualTo("now")
    }

    @Test
    fun `target within 30s of now returns now label`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 15.seconds,
            )
        assertThat(result).isEqualTo("now")
    }

    @Test
    fun `target 1 minute out returns minutes phrase`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 1.minutes,
            )
        assertThat(result).isEqualTo("in 1 min")
    }

    @Test
    fun `target 3 minutes out returns minutes phrase`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 3.minutes,
            )
        assertThat(result).isEqualTo("in 3 min")
    }

    @Test
    fun `target 59 minutes out returns minutes phrase`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 59.minutes,
            )
        assertThat(result).isEqualTo("in 59 min")
    }

    @Test
    fun `target exactly one hour out returns hours phrase without minutes`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 1.hours,
            )
        assertThat(result).isEqualTo("in 1 h")
    }

    @Test
    fun `target 1h12min out returns combined hours and minutes phrase`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 1.hours + 12.minutes,
            )
        assertThat(result).isEqualTo("in 1 h 12 min")
    }

    @Test
    fun `target 23h59min out still in hours bucket`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 23.hours + 59.minutes,
            )
        assertThat(result).isEqualTo("in 23 h 59 min")
    }

    @Test
    fun `target exactly 1 day out returns singular days phrase`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 1.days,
            )
        assertThat(result).isEqualTo("in 1 day")
    }

    @Test
    fun `target 3 days out returns plural days phrase`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now + 3.days,
            )
        assertThat(result).isEqualTo("in 3 days")
    }

    @Test
    fun `target in the past returns departed label`() {
        val result =
            formatter.format(
                scheduled = now,
                estimated = now - 5.minutes,
            )
        assertThat(result).isEqualTo("departed")
    }

    @Test
    fun `null estimated with future scheduled returns scheduled label`() {
        val result = formatter.format(scheduled = now + 10.minutes, estimated = null)
        assertThat(result).isEqualTo("scheduled")
    }

    @Test
    fun `null estimated with past scheduled returns departed label`() {
        val result = formatter.format(scheduled = now - 5.minutes, estimated = null)
        assertThat(result).isEqualTo("departed")
    }

    @Test
    fun `null estimated with scheduled at now returns now label`() {
        val result = formatter.format(scheduled = now, estimated = null)
        assertThat(result).isEqualTo("now")
    }

    @Test
    fun `null estimated within 30s of now returns now label`() {
        val result = formatter.format(scheduled = now + 10.seconds, estimated = null)
        assertThat(result).isEqualTo("now")
    }

    @Test
    fun `estimated takes precedence over scheduled when both supplied`() {
        // Scheduled is two minutes out, estimated says it's actually four minutes out (delayed).
        // The formatter reports the live prediction.
        val result =
            formatter.format(
                scheduled = now + 2.minutes,
                estimated = now + 4.minutes,
            )
        assertThat(result).isEqualTo("in 4 min")
    }

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }
}
