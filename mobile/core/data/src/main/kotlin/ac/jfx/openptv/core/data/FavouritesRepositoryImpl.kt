package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.database.dao.FavouriteRouteAtStopDao
import ac.jfx.openptv.core.database.entity.FavouriteRouteAtStopEntity
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import javax.inject.Inject

/**
 * Default impl. Maps Room entities into the domain [FavouriteRouteAtStop] at the boundary so the
 * upstream surface (use cases, ViewModels) never sees the entity. The DAO and the entity are
 * `:core:database` types and stay confined to this module's impl.
 *
 * `add(...)` reads the current set once (`observe().first()`) to compute the next `position` and
 * then upserts. The two operations aren't wrapped in a Room transaction because a same-key
 * conflict is fine — `@Upsert` semantically already handles "re-favouriting", and a race between
 * two concurrent `add`s for *different* keys at most yields two rows with the same `position` for
 * a single emission, which the next user-initiated `reorder` (or the favourites screen's
 * stable-sort fallback by `addedAt` — to be implemented in issue #35) renormalises. Pragmatic vs.
 * a heavier `@Transaction` insert path the DAO doesn't have today.
 *
 * `isFavourite` derives from the existing `observe()` flow rather than a second DAO query. The
 * favourites list is small (most users keep < 20), the filter is O(n), Room already conflates the
 * underlying flow on row identity, and `distinctUntilChanged` collapses no-op boolean ticks.
 * Adding a dedicated DAO query would buy us nothing here.
 */
internal class FavouritesRepositoryImpl
    @Inject
    constructor(
        private val dao: FavouriteRouteAtStopDao,
        private val clock: Clock,
    ) : FavouritesRepository {
        override fun observe(): Flow<List<FavouriteRouteAtStop>> =
            dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        @Suppress("LongParameterList")
        override suspend fun add(
            stopId: StopId,
            routeType: RouteType,
            routeId: RouteId,
            directionId: DirectionId,
            stopName: String,
            stopSuburb: String,
            routeNumber: String,
            routeName: String,
            directionName: String,
            lat: Double,
            lng: Double,
        ) {
            // Snapshot the current set once so the next position lands at the tail. The race
            // window with a concurrent `add` for a different key is a non-issue (see class kdoc).
            val current = dao.observeAll().first()
            val nextPosition = (current.maxOfOrNull { it.position } ?: -1) + 1
            dao.upsert(
                FavouriteRouteAtStopEntity(
                    stopId = stopId.value,
                    routeType = routeType,
                    routeId = routeId.value,
                    directionId = directionId.value,
                    stopName = stopName,
                    stopSuburb = stopSuburb,
                    routeNumber = routeNumber,
                    routeName = routeName,
                    directionName = directionName,
                    lat = lat,
                    lng = lng,
                    position = nextPosition,
                    addedAt = clock.now().toEpochMilliseconds(),
                ),
            )
        }

        override suspend fun remove(
            stopId: StopId,
            routeId: RouteId,
            directionId: DirectionId,
        ) {
            dao.delete(
                stopId = stopId.value,
                routeId = routeId.value,
                directionId = directionId.value,
            )
        }

        override suspend fun reorder(orderedIds: List<Triple<Int, Int, Int>>) {
            dao.reorder(orderedIds)
        }

        override fun isFavourite(
            stopId: StopId,
            routeId: RouteId,
            directionId: DirectionId,
        ): Flow<Boolean> =
            dao.observeAll()
                .map { rows ->
                    rows.any {
                        it.stopId == stopId.value &&
                            it.routeId == routeId.value &&
                            it.directionId == directionId.value
                    }
                }
                .distinctUntilChanged()
    }
