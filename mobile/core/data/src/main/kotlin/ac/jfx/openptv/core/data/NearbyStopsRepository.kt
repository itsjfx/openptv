package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop

/**
 * Repository surface for "stops near a point". Used by `:feature:nearby` (issue #37) to drive
 * map pins on camera-idle. The interface lives in `:core:data` (per project convention — see
 * `:core:data`/CLAUDE.md), the network-backed impl is wired by Hilt via [DataModule].
 *
 * Errors fold into [Result.Error] rather than throwing, matching the rest of the data layer
 * (`StopSearchRepository`, `StopDetailRepository`, etc).
 */
interface NearbyStopsRepository {
    /**
     * Returns the stops within [radiusMeters] of [coordinates]. Empty list on success means the
     * region has no PTV stops (regional Victoria edge cases) — distinct from a wrapped error.
     */
    suspend fun stopsNear(
        coordinates: Coordinates,
        radiusMeters: Int,
    ): Result<List<Stop>>
}
