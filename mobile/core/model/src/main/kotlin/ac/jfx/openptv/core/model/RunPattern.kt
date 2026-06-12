package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant

/**
 * The stopping pattern of a single service run (issue #132): every stop the run calls at, in
 * chronological order, with the scheduled time and (where PTV has a prediction) the live
 * estimate per stop.
 *
 * `route` is nullable because the `routes` sideload occasionally misses the run's `route_id`
 * (PTV's endpoints disagree on route coverage now and then — same defensive posture as the
 * stop-detail badge fallback). The screen falls back to a generic title when it's absent.
 *
 * `directionName` is the run's destination as PTV labels it ("Flinders Street", "Mernda") —
 * resolved from the `directions` sideload at mapping time so the UI never joins maps itself.
 */
data class RunPattern(
    val route: Route?,
    val directionName: String,
    val stops: List<RunPatternStop>,
)

/**
 * One stop on a run's pattern. Unlike [Departure] (which is "a service leaving *this* stop"),
 * a pattern stop is "this service calling at *that* stop" — so it carries the stop's identity
 * and display name alongside the times.
 */
data class RunPatternStop(
    val stopId: StopId,
    val stopName: String,
    val stopSuburb: String,
    val scheduledDepartureUtc: Instant,
    val estimatedDepartureUtc: Instant?,
    val platform: PlatformNumber?,
)
