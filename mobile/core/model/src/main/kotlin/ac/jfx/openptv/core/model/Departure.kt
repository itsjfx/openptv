package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant

/**
 * Live departure projection for the stop-detail screen. Pulled from PTV's
 * `GET /v3/departures/route_type/{route_type}/stop/{stop_id}` endpoint and reshaped at the
 * `:core:network` boundary so domain consumers never see the wire DTOs.
 *
 * Times come through as UTC [Instant]s. The screen-level [Clock] decides what "now" means; the
 * formatter in `:core:common` converts these to relative strings. Keeping the field type as
 * `Instant` (rather than a pre-formatted string) means the Glance widget can reuse the same
 * domain object for its much shorter "next departure" label.
 *
 * `estimatedDepartureUtc` is nullable because PTV omits it when no real-time prediction is
 * available — formatters fall back to the scheduled time and label it accordingly.
 *
 * `disruptions` are the PTV disruptions affecting this specific run, joined from the response's
 * sideloaded `disruptions` map at the `:core:network` boundary (issue #177). Empty when the run is
 * running to plan. [hasDisruption] is the convenience predicate the stop-detail row reads to decide
 * whether to render the warning indicator.
 */
data class Departure(
    val routeId: RouteId,
    val runRef: RunRef,
    val scheduledDepartureUtc: Instant,
    val estimatedDepartureUtc: Instant?,
    val platform: PlatformNumber?,
    val direction: Direction,
    val disruptions: List<Disruption> = emptyList(),
) {
    /** True when at least one disruption affects this run — drives the row's warning indicator. */
    val hasDisruption: Boolean
        get() = disruptions.isNotEmpty()
}

/**
 * PTV route identifier. Stop detail groups departures by `(routeId, direction)` so a `value class`
 * is enough — we never need to project this back to PTV's full Route DTO at the domain level.
 */
@JvmInline
value class RouteId(val value: Int)

/**
 * PTV run reference — opaque identifier for "this specific trip on this specific day". Stopping
 * pattern (Phase 09) takes this as the key to look up the timeline. Kept as a string because PTV
 * recently switched the canonical reference format from int to a longer mixed-case token.
 */
@JvmInline
value class RunRef(val value: String)

/**
 * Platform / stand at the boarding stop. Trains report numeric platforms ("1", "2"); trams /
 * buses occasionally report letters or stand codes. Kept as a string so unusual values pass
 * through unchanged. Nullable parent field on [Departure] handles the "no platform info" case.
 */
@JvmInline
value class PlatformNumber(val value: String)

/**
 * Direction of travel — `directionId` is the PTV-internal key (used for grouping and for the
 * stopping-pattern query in Phase 09); `name` is the human-readable destination ("North Coburg").
 */
data class Direction(
    val id: DirectionId,
    val name: String,
)

@JvmInline
value class DirectionId(val value: Int)

/**
 * Departures at a stop alongside the [Route] projections PTV sideloads in the same response. The
 * favourites screen joins each departure's `routeId` back to a [Route] so it can render the line
 * name on the row badge (issue #137 regression — without this the helper falls back to
 * `#<routeId>`). Other consumers (stop-detail, nearby) load `servingRoutes` separately via the
 * stop-detail endpoint and don't need this projection — they only consume [departures].
 */
data class DeparturesAtStop(
    val departures: List<Departure>,
    val routes: List<Route>,
)
