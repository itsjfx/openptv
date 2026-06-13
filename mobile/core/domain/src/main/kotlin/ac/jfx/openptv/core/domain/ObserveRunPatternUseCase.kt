package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.RunPatternRepository
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe the stopping pattern of a single service run (issue #132).
 *
 * Returns a `Flow<Result<RunPattern>>` that re-emits on the repository's 30 s tick and emits
 * [Result.Loading] each time a refresh is in flight — the same lifecycle contract as
 * [ObserveDeparturesUseCase] (collector lifetime drives polling; ViewModels wrap collection in
 * `repeatOnLifecycle(RESUMED)`).
 *
 * Pure pass-through today; lives behind a use case so future filtering (e.g. trimming
 * long-departed stops server data still includes) slots in without touching the ViewModel.
 */
class ObserveRunPatternUseCase
    @Inject
    constructor(
        private val repository: RunPatternRepository,
    ) {
        operator fun invoke(
            runRef: RunRef,
            routeType: RouteType,
        ): Flow<Result<RunPattern>> = repository.observeRunPattern(runRef, routeType)
    }
