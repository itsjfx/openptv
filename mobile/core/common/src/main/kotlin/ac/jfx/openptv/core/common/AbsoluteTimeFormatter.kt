package ac.jfx.openptv.core.common

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Formats an [Instant] as a short clock-face string for the device-local time zone — the
 * absolute counterpart to [RelativeTimeFormatter]. Used by the stop-detail screen's per-row
 * "Scheduled HH:mm" subtext, the "As of HH:mm" footer, and the favourites row's
 * scheduled / live clock-time pair.
 *
 * Pure function — no Android deps. The caller decides whether the device wants a 24-hour or
 * 12-hour face by reading `LocalTimeFormat` (the user's [ac.jfx.openptv.core.datastore.preference.TimeFormatPreference])
 * and folding in the system 24-hour flag at the composition / ViewModel boundary; this
 * formatter never reaches for `android.text.format.DateFormat` directly so it stays trivially
 * unit-testable on the JVM.
 *
 * 24-hour shape: `15:30`. 12-hour shape: `3:30 PM` (no leading zero on the hour; uppercase
 * AM/PM separated by a regular space). Both shapes always show minutes with a leading zero.
 */
object AbsoluteTimeFormatter {
    /**
     * Format [instant] as `HH:mm` (when [use24Hour]) or `h:mm AM/PM` (when not) in the system
     * default time zone. The 12-hour case maps midnight to `12:00 AM` and noon to `12:00 PM` —
     * standard wall-clock convention.
     */
    fun format(
        instant: Instant,
        use24Hour: Boolean,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = instant.toLocalDateTime(timeZone)
        return if (use24Hour) {
            "%02d:%02d".format(local.hour, local.minute)
        } else {
            val hour12 = ((local.hour + ELEVEN) % HOURS_IN_HALF_DAY) + 1
            val amPm = if (local.hour < HOURS_IN_HALF_DAY) "AM" else "PM"
            "%d:%02d %s".format(hour12, local.minute, amPm)
        }
    }

    // Inline the magic numbers behind names to satisfy detekt's MagicNumber rule without
    // introducing a companion or `const`s on a different scope.
    private const val HOURS_IN_HALF_DAY = 12
    private const val ELEVEN = 11
}
