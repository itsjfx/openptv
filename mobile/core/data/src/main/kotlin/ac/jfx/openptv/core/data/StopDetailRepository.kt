package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId

/**
 * One-shot fetch for the stop-detail header (stop metadata + serving routes). Domain callers
 * see only this interface; the network-backed impl is wired by Hilt via [DataModule].
 *
 * Errors are folded into [Result.Error] rather than thrown — pattern-match the result.
 */
interface StopDetailRepository {
    suspend fun getStopDetail(
        stopId: StopId,
        routeType: RouteType,
    ): Result<StopDetail>
}
