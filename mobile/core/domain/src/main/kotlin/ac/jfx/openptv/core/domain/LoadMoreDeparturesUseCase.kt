package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * Use case: fetch the next page of departures anchored at [after]. Powers both the "show more"
 * tap on a collapsed route group and the bottom-of-list scroll that reaches into the next
 * calendar day. Pure pass-through today; same shape as [ObserveDeparturesUseCase] so the
 * ViewModel layer never reaches across the repository boundary.
 */
class LoadMoreDeparturesUseCase
    @Inject
    constructor(
        private val repository: DepartureRepository,
    ) {
        suspend operator fun invoke(
            stopId: StopId,
            routeType: RouteType,
            after: Instant,
            maxResults: Int,
        ): Result<List<Departure>> = repository.loadMore(stopId, routeType, after, maxResults)
    }
