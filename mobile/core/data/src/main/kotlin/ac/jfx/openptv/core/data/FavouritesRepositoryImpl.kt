package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.database.dao.FavouriteDestinationAtStopDao
import ac.jfx.openptv.core.database.entity.FavouriteDestinationAtStopEntity
import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import javax.inject.Inject

/**
 * Default impl. Maps Room entities into the domain [FavouriteDestinationAtStop] at the boundary
 * so the upstream surface never sees the entity.
 *
 * `add(...)` reads the current set once (`observe().first()`) to compute the next `position` and
 * then upserts. The two operations aren't wrapped in a Room transaction because a same-key
 * conflict is fine — `@Upsert` semantically already handles "re-favouriting", and a race between
 * two concurrent `add`s for *different* keys at most yields two rows with the same `position` for
 * a single emission, which the next user-initiated `reorder` renormalises.
 *
 * `isFavourite` derives from `observe()` rather than a second DAO query — the favourites list is
 * small, the filter is O(n), and `distinctUntilChanged` collapses no-op boolean ticks.
 */
internal class FavouritesRepositoryImpl
    @Inject
    constructor(
        private val dao: FavouriteDestinationAtStopDao,
        private val clock: Clock,
    ) : FavouritesRepository {
        override fun observe(): Flow<List<FavouriteDestinationAtStop>> =
            dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        @Suppress("LongParameterList")
        override suspend fun add(
            stopId: StopId,
            destinationKey: String,
            routeType: RouteType,
            stopName: String,
            stopSuburb: String,
            destinationName: String,
            lat: Double,
            lng: Double,
        ) {
            val current = dao.observeAll().first()
            val nextPosition = (current.maxOfOrNull { it.position } ?: -1) + 1
            dao.upsert(
                FavouriteDestinationAtStopEntity(
                    stopId = stopId.value,
                    destinationKey = destinationKey,
                    routeType = routeType,
                    stopName = stopName,
                    stopSuburb = stopSuburb,
                    destinationName = destinationName,
                    lat = lat,
                    lng = lng,
                    position = nextPosition,
                    addedAt = clock.now().toEpochMilliseconds(),
                ),
            )
        }

        override suspend fun remove(
            stopId: StopId,
            destinationKey: String,
        ) {
            dao.delete(stopId = stopId.value, destinationKey = destinationKey)
        }

        override suspend fun reorder(orderedKeys: List<Pair<Int, String>>) {
            dao.reorder(orderedKeys)
        }

        override fun isFavourite(
            stopId: StopId,
            destinationKey: String,
        ): Flow<Boolean> =
            dao.observeAll()
                .map { rows ->
                    rows.any {
                        it.stopId == stopId.value && it.destinationKey == destinationKey
                    }
                }
                .distinctUntilChanged()
    }
