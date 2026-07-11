package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.model.AlightAlert
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunPatternStop
import kotlinx.datetime.Instant
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One evaluation of an armed alight alert (issue #201) against the latest run pattern, wall
 * clock, and (optionally) the latest GPS fix.
 *
 * @property stopsAway how many stops the vehicle has yet to reach up to and including the
 *   alight stop, derived from the pattern's times: `1` means "your stop is next" (the vehicle
 *   is at/past the stop one before), `0` means the alight stop's departure time has passed.
 *   Null when the alight stop isn't on the pattern at all (stale run data).
 * @property etaUtc the alight stop's `estimated ?: scheduled` departure — what the ongoing
 *   notification renders and what the pre-arrival stage counts down against.
 * @property hasRealTimeSignal true when any pattern stop carries a live estimate. Trams never
 *   do on the pattern endpoint (CLAUDE.md quirk), which is what the GPS fallback exists for.
 * @property usesGpsFallback true when the stage decisions this evaluation came from GPS
 *   proximity rather than pattern times.
 * @property isScheduleOnly true when there's no real-time signal *and* no usable GPS fix —
 *   the stages will fire off scheduled times only, and the user should be warned.
 * @property fireApproachAlert post the stage-1 ("1 stop before") notification now.
 * @property fireArrivalAlert post the stage-2 (pre-arrival) notification now.
 * @property updatedAlert the alert with fire-once latches advanced and coordinates backfilled
 *   from the pattern — persist this alongside the trip so a service restart never re-fires.
 * @property nextCheckIn adaptive polling hint: ~30 s while far, tighter close to the alight
 *   stop, and an exact countdown to the pre-arrival instant when that lands inside the window.
 */
data class AlightAlertEvaluation(
    val stopsAway: Int?,
    val etaUtc: Instant?,
    val hasRealTimeSignal: Boolean,
    val usesGpsFallback: Boolean,
    val isScheduleOnly: Boolean,
    val fireApproachAlert: Boolean,
    val fireArrivalAlert: Boolean,
    val updatedAlert: AlightAlert,
    val nextCheckIn: Duration,
)

/**
 * Pure decision core for the alight-alert foreground service (issue #201). No Android types, no
 * side effects: pattern + alert + clock (+ optional GPS fix) in, stage decisions out. The
 * service stays thin — it fetches, calls [evaluate], posts what it's told to, persists
 * [AlightAlertEvaluation.updatedAlert], and sleeps for [AlightAlertEvaluation.nextCheckIn].
 *
 * **Stage semantics.**
 * - *Approach* fires when the vehicle is one stop before the alight stop ([stopsAway] == 1).
 * - *Arrival* fires ~[ARRIVAL_LEAD] before the alight stop's ETA, with a [ARRIVAL_LATE_CUTOFF]
 *   grace so a poll that lands just after the instant still alerts, but a service that wakes
 *   long past arrival stays silent (a late blast after the user already got off is worse than
 *   none).
 * - Arrival supersedes approach: when both would fire in one evaluation (armed very late, or a
 *   big estimate jump), only the arrival alert sounds and both latches advance.
 * - Each stage fires at most once per armed alert — the latches live on the persisted
 *   [AlightAlert], and re-arming on a different stop replaces the alert, which resets them.
 *
 * **Signal selection.** Pattern times drive the stages whenever any stop has a live estimate.
 * Without estimates (trams), a GPS fix + known alight coordinates switch the stages to
 * proximity: approach at roughly the previous stop's distance from the alight stop (clamped to
 * [APPROACH_DISTANCE_MIN]..[APPROACH_DISTANCE_MAX], defaulting to [APPROACH_DISTANCE_DEFAULT]
 * when the previous stop has no coordinates), arrival at [ARRIVAL_DISTANCE]. With neither, the
 * scheduled times still drive the stages but [AlightAlertEvaluation.isScheduleOnly] tells the
 * UI to warn.
 */
class AlightAlertEvaluator
    @Inject
    constructor() {
        fun evaluate(
            pattern: RunPattern,
            alert: AlightAlert,
            now: Instant,
            location: Coordinates? = null,
        ): AlightAlertEvaluation {
            val hasRealTime = pattern.stops.any { it.estimatedDepartureUtc != null }
            val alightIndex =
                pattern.alightIndex(alert, now)
                    ?: return alightStopMissingEvaluation(alert, hasRealTime)

            val alightStop = pattern.stops[alightIndex]
            val resolvedCoordinates = alert.coordinates ?: alightStop.coordinates
            val eta = alightStop.departureUtc
            val stopsAway = pattern.stopsAway(alightIndex, now)

            val gpsMode = !hasRealTime && location != null && resolvedCoordinates != null
            val (fireApproach, fireArrival, updatedAlert) =
                stageDecision(
                    pattern = pattern,
                    alightIndex = alightIndex,
                    alert = alert,
                    resolvedCoordinates = resolvedCoordinates,
                    stopsAway = stopsAway,
                    eta = eta,
                    now = now,
                    gpsLocation = location.takeIf { gpsMode },
                )

            return AlightAlertEvaluation(
                stopsAway = stopsAway,
                etaUtc = eta,
                hasRealTimeSignal = hasRealTime,
                usesGpsFallback = gpsMode,
                isScheduleOnly = !hasRealTime && !gpsMode,
                fireApproachAlert = fireApproach,
                fireArrivalAlert = fireArrival,
                updatedAlert = updatedAlert,
                nextCheckIn = nextCheckIn(updatedAlert, stopsAway, eta, now),
            )
        }

        /**
         * Pick the stage signal (GPS proximity when [gpsLocation] is engaged, pattern times
         * otherwise) and advance the fire-once latches. Arrival supersedes approach: both never
         * sound in the same evaluation, but a superseded approach still latches.
         */
        @Suppress("LongParameterList")
        private fun stageDecision(
            pattern: RunPattern,
            alightIndex: Int,
            alert: AlightAlert,
            resolvedCoordinates: Coordinates?,
            stopsAway: Int,
            eta: Instant,
            now: Instant,
            gpsLocation: Coordinates?,
        ): Triple<Boolean, Boolean, AlightAlert> {
            val (approachWanted, arrivalWanted) =
                if (gpsLocation != null && resolvedCoordinates != null) {
                    gpsStages(pattern, alightIndex, resolvedCoordinates, gpsLocation)
                } else {
                    timeStages(stopsAway, eta, now)
                }
            val fireArrival = arrivalWanted && !alert.arrivalFired
            val fireApproach = approachWanted && !alert.approachFired && !fireArrival
            val updatedAlert =
                alert.copy(
                    coordinates = resolvedCoordinates,
                    approachFired = alert.approachFired || fireApproach || fireArrival,
                    arrivalFired = alert.arrivalFired || fireArrival,
                )
            return Triple(fireApproach, fireArrival, updatedAlert)
        }

        /**
         * The alight stop isn't on the pattern (stale run_ref / PTV trimmed the pattern):
         * nothing to decide; keep polling at the far cadence and let the pattern recover.
         */
        private fun alightStopMissingEvaluation(
            alert: AlightAlert,
            hasRealTime: Boolean,
        ): AlightAlertEvaluation =
            AlightAlertEvaluation(
                stopsAway = null,
                etaUtc = null,
                hasRealTimeSignal = hasRealTime,
                usesGpsFallback = false,
                isScheduleOnly = !hasRealTime,
                fireApproachAlert = false,
                fireArrivalAlert = false,
                updatedAlert = alert,
                nextCheckIn = FAR_POLL,
            )

        /**
         * Index of the alert's stop on the pattern: the first *upcoming* occurrence when the
         * stop repeats (city-loop runs call at the same station twice), else the last
         * occurrence, else null when the stop isn't on the run at all.
         */
        private fun RunPattern.alightIndex(
            alert: AlightAlert,
            now: Instant,
        ): Int? {
            val upcoming =
                stops.indexOfFirst { it.stopId == alert.stopId && it.departureUtc > now }
            if (upcoming != -1) return upcoming
            return stops.indexOfLast { it.stopId == alert.stopId }.takeIf { it != -1 }
        }

        /**
         * Stops the vehicle has yet to reach up to and including the alight stop, from the
         * pattern's `estimated ?: scheduled` times: the first stop with a future departure is
         * the vehicle's next stop.
         */
        private fun RunPattern.stopsAway(
            alightIndex: Int,
            now: Instant,
        ): Int {
            val nextStopIndex = stops.indexOfFirst { it.departureUtc > now }
            if (nextStopIndex == -1) return 0 // Whole pattern in the past — run finished.
            return (alightIndex - nextStopIndex + 1).coerceAtLeast(0)
        }

        private fun timeStages(
            stopsAway: Int,
            eta: Instant,
            now: Instant,
        ): Pair<Boolean, Boolean> {
            val approach = stopsAway == 1
            val arrival = now >= eta - ARRIVAL_LEAD && now < eta + ARRIVAL_LATE_CUTOFF && stopsAway <= 1
            return approach to arrival
        }

        private fun gpsStages(
            pattern: RunPattern,
            alightIndex: Int,
            alightCoordinates: Coordinates,
            location: Coordinates,
        ): Pair<Boolean, Boolean> {
            val distance = location.distanceTo(alightCoordinates)
            val previousStopGap =
                pattern.stops.getOrNull(alightIndex - 1)?.coordinates?.distanceTo(alightCoordinates)
            val approachThreshold =
                previousStopGap?.coerceIn(APPROACH_DISTANCE_MIN, APPROACH_DISTANCE_MAX)
                    ?: APPROACH_DISTANCE_DEFAULT
            return (distance <= approachThreshold) to (distance <= ARRIVAL_DISTANCE)
        }

        /**
         * Adaptive cadence. Far out the 30 s departures cadence is plenty; inside two stops (or
         * 90 s of ETA) tighten to 5 s so the approach stage can't slip through a poll gap; and
         * when the *next* interesting instant is the pre-arrival alert itself, sleep exactly
         * until it (clamped to at least 1 s) instead of ticking past it.
         */
        private fun nextCheckIn(
            alert: AlightAlert,
            stopsAway: Int?,
            eta: Instant?,
            now: Instant,
        ): Duration {
            if (eta == null) return FAR_POLL
            if (!alert.arrivalFired) {
                val untilArrivalAlert = (eta - ARRIVAL_LEAD) - now
                if (untilArrivalAlert > Duration.ZERO && untilArrivalAlert <= NEAR_POLL) {
                    return untilArrivalAlert.coerceAtLeast(MIN_POLL)
                }
            }
            val near = (stopsAway != null && stopsAway <= NEAR_STOPS) || (eta - now) <= NEAR_ETA_WINDOW
            return if (near) NEAR_POLL else FAR_POLL
        }

        private val RunPatternStop.departureUtc: Instant
            get() = estimatedDepartureUtc ?: scheduledDepartureUtc

        companion object {
            /** Stage 2 fires this long before the alight stop's ETA (issue #201: ~10-15 s). */
            val ARRIVAL_LEAD: Duration = 15.seconds

            /** Don't late-fire stage 2 once the ETA is this far gone — the user is off the vehicle. */
            val ARRIVAL_LATE_CUTOFF: Duration = 1.minutes

            /** Poll cadence while far from the alight stop — matches the departures poll. */
            val FAR_POLL: Duration = 30.seconds

            /** Poll cadence once the alight stop is close. */
            val NEAR_POLL: Duration = 5.seconds

            /** Floor for the exact pre-arrival wake. */
            val MIN_POLL: Duration = 1.seconds

            /** "Close" in stops: within this many stops tightens the cadence. */
            const val NEAR_STOPS: Int = 2

            /** "Close" in time: within this much ETA tightens the cadence. */
            val NEAR_ETA_WINDOW: Duration = 90.seconds

            /** GPS fallback: stage-2 radius — ~10-15 s of tram travel. */
            const val ARRIVAL_DISTANCE: Double = 75.0

            /** GPS fallback: stage-1 radius when the previous stop has no coordinates. */
            const val APPROACH_DISTANCE_DEFAULT: Double = 500.0

            /** GPS fallback: clamp on the previous-stop-gap approach radius. */
            const val APPROACH_DISTANCE_MIN: Double = 150.0

            /** GPS fallback: clamp on the previous-stop-gap approach radius. */
            const val APPROACH_DISTANCE_MAX: Double = 1_200.0
        }
    }
