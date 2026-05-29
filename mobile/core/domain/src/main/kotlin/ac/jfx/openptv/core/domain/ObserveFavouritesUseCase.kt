package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe the user's favourited destinations.
 *
 * Pure pass-through today; behind the use case so the favourites screen can fold in cross-repo
 * state (next-departure batches, etc.) without the consuming ViewModel growing a second
 * dependency.
 */
class ObserveFavouritesUseCase
    @Inject
    constructor(
        private val repository: FavouritesRepository,
    ) {
        operator fun invoke(): Flow<List<FavouriteDestinationAtStop>> = repository.observe()
    }
