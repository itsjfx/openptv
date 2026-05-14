package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.model.Direction
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.Stop
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case: toggle a `(stopId, routeId, directionId)` favourite on or off.
 *
 * Takes the three domain projections the stop-detail screen already has on hand:
 *  - the [Stop] (from `StopDetail.stop` — gives `stopName`, `stopSuburb`, `lat`, `lng`,
 *    `routeType` for the cached display fields),
 *  - the [Route] (from `StopDetail.servingRoutes` keyed by `routeId` — gives `routeNumber`,
 *    `routeName`),
 *  - the [Direction] (from the group's first departure — gives `directionId` and
 *    `directionName`).
 *
 * Behaviour: read `isFavourite(...)` once; if true → remove, if false → add. The repository's
 * `add(...)` snapshots the next position itself, so we don't have to.
 *
 * Returns the resulting state (`true` if now favourited, `false` if now unfavourited) — useful
 * for analytics or for the UI to show a confirmation Snackbar if it ever wants one.
 */
class ToggleFavouriteUseCase
    @Inject
    constructor(
        private val repository: FavouritesRepository,
    ) {
        suspend operator fun invoke(
            stop: Stop,
            route: Route,
            direction: Direction,
        ): Boolean {
            val currentlyFavourite =
                repository.isFavourite(
                    stopId = stop.id,
                    routeId = route.id,
                    directionId = direction.id,
                ).first()
            if (currentlyFavourite) {
                repository.remove(
                    stopId = stop.id,
                    routeId = route.id,
                    directionId = direction.id,
                )
                return false
            }
            repository.add(
                stopId = stop.id,
                routeType = route.routeType,
                routeId = route.id,
                directionId = direction.id,
                stopName = stop.name,
                stopSuburb = stop.suburb,
                routeNumber = route.number,
                routeName = route.name,
                directionName = direction.name,
                lat = stop.latitude,
                lng = stop.longitude,
            )
            return true
        }
    }
