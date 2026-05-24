package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.FavouritesRepository
import javax.inject.Inject

/**
 * Use case: re-number the user's favourites to match a new manual order. Pass-through to the
 * repository's transactional reorder. The consumer hands in the post-drag list of
 * `(stopId, destinationKey)` pairs.
 */
class ReorderFavouritesUseCase
    @Inject
    constructor(
        private val repository: FavouritesRepository,
    ) {
        suspend operator fun invoke(orderedKeys: List<Pair<Int, String>>) = repository.reorder(orderedKeys)
    }
