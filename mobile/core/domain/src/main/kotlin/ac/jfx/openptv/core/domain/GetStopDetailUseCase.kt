package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.StopDetailRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import javax.inject.Inject

/**
 * Use case: load a single [StopDetail].
 *
 * Today this is a pure pass-through to [StopDetailRepository]. It exists so the stop-detail
 * ViewModel depends on a stable domain seam rather than the repository interface directly —
 * future phases will likely add cross-repo orchestration here (e.g. fold in user-favourites
 * state once Phase 04 lands, so the screen knows whether the star is filled).
 *
 * `operator fun invoke` so callers write `useCase(stopId, routeType)` per the conventions doc.
 */
class GetStopDetailUseCase
    @Inject
    constructor(
        private val repository: StopDetailRepository,
    ) {
        suspend operator fun invoke(
            stopId: StopId,
            routeType: RouteType,
        ): Result<StopDetail> = repository.getStopDetail(stopId, routeType)
    }
