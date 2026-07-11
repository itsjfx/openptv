package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant

/**
 * One direct service from an origin stop to a destination stop (issue #204): a single run the
 * user can board at the origin and alight from at the destination with no transfer. Derived
 * client-side from PTV departures + run patterns — the v3 API has no journey-planning endpoint.
 *
 * Departure fields describe the run at the *origin* stop; arrival fields describe the same run
 * at the *destination* stop. Arrival times come from either the destination's own departures
 * feed (when the run continues past it — PTV reports the departure instant, which is within a
 * minute of arrival for every mode we serve) or the run's stopping pattern (when the run
 * terminates at the destination and therefore never appears in its departures feed).
 *
 * `estimatedArrivalUtc` is nullable for the same reason as [Departure.estimatedDepartureUtc]:
 * PTV omits real-time predictions per mode (trams never carry them on patterns) and per run.
 */
data class JourneyOption(
    val route: Route,
    val direction: Direction,
    val runRef: RunRef,
    val scheduledDepartureUtc: Instant,
    val estimatedDepartureUtc: Instant?,
    val departurePlatform: PlatformNumber?,
    val scheduledArrivalUtc: Instant,
    val estimatedArrivalUtc: Instant?,
    val disruptions: List<Disruption> = emptyList(),
) {
    /** Best-known departure instant — the live estimate when PTV has one, else the timetable. */
    val effectiveDepartureUtc: Instant
        get() = estimatedDepartureUtc ?: scheduledDepartureUtc

    /** Best-known arrival instant — the live estimate when PTV has one, else the timetable. */
    val effectiveArrivalUtc: Instant
        get() = estimatedArrivalUtc ?: scheduledArrivalUtc

    /** True when at least one disruption affects this run — drives the row's warning indicator. */
    val hasDisruption: Boolean
        get() = disruptions.isNotEmpty()
}
