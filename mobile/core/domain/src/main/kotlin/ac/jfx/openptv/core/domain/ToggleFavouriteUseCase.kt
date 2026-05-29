package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.toDestinationKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case: toggle a `(stopId, destinationKey)` favourite on or off.
 *
 * Takes:
 *  - the [Stop] (gives `stopName`, `stopSuburb`, `lat`, `lng`, `routeType` for the cached
 *    display fields),
 *  - the user-facing destination name (e.g. "City") — the use case computes the key via
 *    [toDestinationKey] so the normalisation rule lives in one place.
 *
 * Behaviour: read `isFavourite(...)` once; if true → remove, if false → add. The repository's
 * `add(...)` snapshots the next position itself, so we don't have to.
 *
 * Returns the resulting state (`true` if now favourited, `false` if now unfavourited).
 */
class ToggleFavouriteUseCase
    @Inject
    constructor(
        private val repository: FavouritesRepository,
    ) {
        suspend operator fun invoke(
            stop: Stop,
            destinationName: String,
        ): Boolean {
            val destinationKey = destinationName.toDestinationKey()
            val currentlyFavourite =
                repository.isFavourite(stopId = stop.id, destinationKey = destinationKey).first()
            if (currentlyFavourite) {
                repository.remove(stopId = stop.id, destinationKey = destinationKey)
                return false
            }
            repository.add(
                stopId = stop.id,
                destinationKey = destinationKey,
                routeType = stop.routeType,
                stopName = stop.name,
                stopSuburb = stop.suburb,
                destinationName = destinationName,
                lat = stop.latitude,
                lng = stop.longitude,
            )
            return true
        }
    }
