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
 * `flags` is reserved for future per-departure indicators (cancelled, replaced by bus, etc.).
 * Phase 03 wires only a placeholder; richer values land in Phase 08+.
 */
data class Departure(
    val routeId: RouteId,
    val runRef: RunRef,
    val scheduledDepartureUtc: Instant,
    val estimatedDepartureUtc: Instant?,
    val platform: PlatformNumber?,
    val direction: Direction,
    val flags: DepartureFlags = DepartureFlags(),
)

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
 * Per-departure flags. Reserved for future use — cancellation, replacement bus, skip-stop notice.
 * Phase 03 only models the disruption-flagged bit because the UI shows a placeholder icon for it
 * even though the disruption browser itself (Phase 10) isn't shipped yet.
 */
data class DepartureFlags(
    val hasDisruption: Boolean = false,
)

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
