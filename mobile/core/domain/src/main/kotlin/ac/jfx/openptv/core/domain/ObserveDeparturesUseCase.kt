package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * Use case: observe live departures at a stop.
 *
 * Returns a `Flow<Result<List<Departure>>>` that re-emits on the repository's 30 s tick and
 * emits [Result.Loading] each time a refresh is in flight. Collector lifetime drives the
 * polling loop — the ViewModel layer is expected to wrap this in `repeatOnLifecycle(RESUMED)`
 * so polling pauses while the screen is backgrounded.
 *
 * Pure pass-through today; lives behind a use case so future ordering / filtering (e.g.
 * "hide departed runs older than two minutes", "promote starred routes to the top") slots in
 * without touching the ViewModel.
 *
 * [at] (issue #182) optionally anchors the stream at a chosen instant instead of live "now". The
 * ViewModel passes it through verbatim; `null` keeps the live-polling behaviour.
 */
class ObserveDeparturesUseCase
    @Inject
    constructor(
        private val repository: DepartureRepository,
    ) {
        operator fun invoke(
            stopId: StopId,
            routeType: RouteType,
            at: Instant? = null,
        ): Flow<Result<List<Departure>>> = repository.observeDepartures(stopId, routeType, at)
    }
