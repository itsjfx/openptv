package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe the user's favourited services.
 *
 * Pure pass-through today; behind the use case so the future favourites screen (issue #35) can
 * fold in cross-repo state (most-recent next-departure batches, search-as-you-type filtering)
 * without the consuming ViewModel growing a second dependency.
 */
class ObserveFavouritesUseCase
    @Inject
    constructor(
        private val repository: FavouritesRepository,
    ) {
        operator fun invoke(): Flow<List<FavouriteRouteAtStop>> = repository.observe()
    }
