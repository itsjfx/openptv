package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop

/**
 * Repository surface for "stops near a point". Used by `:feature:nearby` (issues #37 + #79) to
 * drive map pins on camera-idle. The interface lives in `:core:data` (per project convention —
 * see `:core:data`/CLAUDE.md), the network-backed impl is wired by Hilt via [DataModule].
 *
 * Errors fold into [Result.Error] rather than throwing, matching the rest of the data layer
 * (`StopSearchRepository`, `StopDetailRepository`, etc).
 */
interface NearbyStopsRepository {
    /**
     * Returns the stops within [radiusMeters] of [coordinates]. Empty list on success means the
     * region has no PTV stops (regional Victoria edge cases) — distinct from a wrapped error.
     *
     * [routeTypes] narrows the response to those PTV transport modes; PTV repeats the
     * `route_types` query parameter once per requested mode. Passing an empty set means "all
     * types" (the same behaviour PTV gives when the parameter is omitted) — this keeps the
     * common case ergonomic and means callers don't have to enumerate every [RouteType] when
     * they want everything.
     *
     * [RouteType.Unknown] is dropped before the wire call: it is the runtime fallback for an
     * unrecognised PTV code, never a request value, so passing it would be a bug. Callers that
     * really do want everything should pass an empty set.
     */
    suspend fun stopsNear(
        coordinates: Coordinates,
        radiusMeters: Int,
        routeTypes: Set<RouteType> = emptySet(),
    ): Result<List<Stop>>
}
