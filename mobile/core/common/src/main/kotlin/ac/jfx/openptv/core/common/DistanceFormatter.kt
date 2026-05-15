package ac.jfx.openptv.core.common

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Formats a great-circle distance (typically [ac.jfx.openptv.core.model.Coordinates.distanceTo])
 * as a short label suitable for a list-row subtext. Mirrors what Google Maps / the official PTV
 * app surface beside a nearby-stop row:
 *
 *  - `< 1 km` → metres rounded to the nearest 10 m, e.g. `"120 m"` / `"50 m"`.
 *  - `>= 1 km` and `< 10 km` → one decimal place of km, e.g. `"1.2 km"` / `"3.4 km"`.
 *  - `>= 10 km` → whole kilometres, e.g. `"12 km"`.
 *
 * The thresholds mirror Material's "compact distance" guidance — under 1 km the eye reads metres
 * naturally; over 10 km a decimal looks fussy when the user is just trying to gauge "is this
 * close-ish".
 *
 * Singleton because the type is stateless. Hilt-injectable so a unit test can construct it
 * directly without going through a graph.
 */
@Singleton
class DistanceFormatter
    @Inject
    constructor() {
        /**
         * Format [metres] as a compact label. Negative values are clamped to 0 so a rare
         * "user fix is a metre south of the stop, haversine returns a tiny negative" never
         * surfaces a `-0 m` to the user.
         */
        @Suppress("MagicNumber")
        fun format(metres: Double): String {
            val clamped = metres.coerceAtLeast(0.0)
            return when {
                clamped < METRES_PER_KM -> {
                    val rounded = ((clamped / METRES_ROUND_STEP).toInt()) * METRES_ROUND_STEP
                    "$rounded m"
                }
                clamped < KM_DECIMAL_THRESHOLD * METRES_PER_KM -> {
                    val km = clamped / METRES_PER_KM
                    val tenths = (km * 10).toInt()
                    val whole = tenths / 10
                    val decimal = tenths % 10
                    "$whole.$decimal km"
                }
                else -> {
                    val km = (clamped / METRES_PER_KM).toInt()
                    "$km km"
                }
            }
        }

        private companion object {
            private const val METRES_PER_KM = 1_000.0

            /**
             * Below 1 km we round to the nearest 10 m. The user doesn't care that a stop is 87 m
             * away vs. 80 m — it's "across the road". 10 m is the smallest step that still feels
             * like "a number" rather than a GPS reading.
             */
            private const val METRES_ROUND_STEP = 10

            /** Above 10 km the decimal looks fussy — switch to whole kilometres. */
            private const val KM_DECIMAL_THRESHOLD = 10
        }
    }
