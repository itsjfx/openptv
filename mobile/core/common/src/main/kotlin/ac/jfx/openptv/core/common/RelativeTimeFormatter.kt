package ac.jfx.openptv.core.common

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Formats a departure [Instant] as a short relative string for the stop-detail screen and the
 * Glance widget. The strings are intentionally tight ("in 3 min", not "in 3 minutes") because the
 * row also shows the absolute scheduled time and platform — verbosity adds noise without
 * information.
 *
 * The decision tree:
 *
 *  - `estimated` is `null` and `scheduled` is at/after now → `"scheduled"`.
 *  - `estimated` is `null` and `scheduled` is in the past → `"departed"`.
 *  - Departure is within ±NOW_THRESHOLD of "now" → `"now"`.
 *  - Departure has passed (delta < -NOW_THRESHOLD) → `"departed"`.
 *  - Less than one hour out → `"in N min"`.
 *  - Less than one day out → `"in H h MM min"` (omit the minutes part if zero).
 *  - One day or more out → `"in N day(s)"`.
 *
 * The `Clock` is injected so tests pin "now" to a fixed [Instant]; production binds
 * `Clock.System` via [CommonModule]. Singleton because the type is stateless aside from its
 * `Clock` dependency.
 */
@Singleton
class RelativeTimeFormatter
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        /**
         * Format the next departure as a relative phrase. Pass [scheduled] for the timetable
         * value and [estimated] for the real-time prediction (nullable: PTV omits it when no
         * prediction exists).
         */
        fun format(
            scheduled: Instant,
            estimated: Instant? = null,
        ): String {
            val target = estimated ?: return formatScheduledOnly(scheduled)
            val delta = target - clock.now()
            return when {
                delta.isWithinNowWindow() -> NOW_LABEL
                delta.isNegative() -> DEPARTED_LABEL
                delta < ONE_HOUR -> formatMinutes(delta)
                delta < ONE_DAY -> formatHoursAndMinutes(delta)
                else -> formatDays(delta)
            }
        }

        private fun formatScheduledOnly(scheduled: Instant): String {
            val delta = scheduled - clock.now()
            return when {
                delta.isWithinNowWindow() -> NOW_LABEL
                delta.isNegative() -> DEPARTED_LABEL
                else -> SCHEDULED_LABEL
            }
        }

        /**
         * True iff the departure has passed by more than the `now` grace window — i.e. iff
         * [format] would return `"departed"`. Callers use this to drop stale rows from the
         * stop-detail list. Aligning on the same threshold means a row labelled `"now"` is
         * never filtered out, and a row labelled `"departed"` is never shown.
         */
        fun isDeparted(
            scheduled: Instant,
            estimated: Instant? = null,
        ): Boolean {
            val target = estimated ?: scheduled
            val delta = target - clock.now()
            return delta.isNegative() && !delta.isWithinNowWindow()
        }

        // ±NOW_THRESHOLD of the target counts as "now" rather than "in 0 min" / "departed".
        private fun Duration.isWithinNowWindow(): Boolean = abs(this.inWholeSeconds) <= NOW_THRESHOLD.inWholeSeconds

        private fun formatMinutes(delta: Duration): String = "in ${delta.inWholeMinutes} min"

        private fun formatHoursAndMinutes(delta: Duration): String {
            val hours = delta.inWholeHours
            val minutes = (delta - hours.hours).inWholeMinutes
            return if (minutes == 0L) "in $hours h" else "in $hours h $minutes min"
        }

        private fun formatDays(delta: Duration): String {
            val daysOut = delta.inWholeDays
            return if (daysOut == 1L) "in 1 day" else "in $daysOut days"
        }

        private companion object {
            // Window of "the row still says 'now' and stays on screen" around the target
            // instant. Two minutes accommodates real-world slop — services often depart a
            // touch late even when the live feed has caught up to zero — without rows
            // lingering long enough to feel stale.
            private val NOW_THRESHOLD: Duration = 2.minutes
            private val ONE_HOUR: Duration = 1.hours
            private val ONE_DAY: Duration = 1.days

            private const val NOW_LABEL = "now"
            private const val DEPARTED_LABEL = "departed"
            private const val SCHEDULED_LABEL = "scheduled"
        }
    }
