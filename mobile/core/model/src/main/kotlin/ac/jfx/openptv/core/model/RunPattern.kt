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
 *
 * `directionId` is the run's PTV direction identifier, captured so the data layer can pick the
 * matching geopath segment from the `stops/route` endpoint (which returns one segment per
 * direction). Null when the pattern envelope had no departures to read it from.
 *
 * `geopath` is the route line drawn on the run-pattern map (issue #187): zero or more polyline
 * segments, each an ordered list of [Coordinates]. Empty when PTV returned no geopath — the map
 * degrades gracefully to "stops only" (or hides entirely) in that case. Filled lazily by the
 * data layer via a second `stops/route?include_geopath=true` call, because the pattern endpoint
 * itself returns `geopath: null` in practice even with `include_geopath=true` (see
 * `RunPatternRepositoryImpl`).
 */
data class RunPattern(
    val route: Route?,
    val directionName: String,
    val stops: List<RunPatternStop>,
    val directionId: Int? = null,
    val geopath: List<List<Coordinates>> = emptyList(),
)

/**
 * One stop on a run's pattern. Unlike [Departure] (which is "a service leaving *this* stop"),
 * a pattern stop is "this service calling at *that* stop" — so it carries the stop's identity
 * and display name alongside the times.
 *
 * `coordinates` is the stop's geographic position, used to draw the stop marker on the run-pattern
 * map (issue #187). Null when PTV hasn't supplied a location for the stop — the pattern endpoint's
 * own stop sideload omits geo fields, so this is populated by the data layer's join against the
 * `stops/route` payload. A null-coordinate stop is simply not drawn on the map; it still renders
 * in the text timeline, which never depends on geometry.
 */
data class RunPatternStop(
    val stopId: StopId,
    val stopName: String,
    val stopSuburb: String,
    val scheduledDepartureUtc: Instant,
    val estimatedDepartureUtc: Instant?,
    val platform: PlatformNumber?,
    val coordinates: Coordinates? = null,
)
