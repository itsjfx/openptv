package ac.jfx.openptv.core.database.dao

import ac.jfx.openptv.core.database.entity.FavouriteRouteAtStopEntity
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [FavouriteRouteAtStopEntity]. The repository (in `:core:data`,
 * lands in a follow-up PR) consumes this directly; consumers further upstream see only the
 * domain model.
 *
 * `observeAll` is ordered by `position` so the UI doesn't need a second sort. Room conflates
 * the `Flow` by default — DAO tests assert re-emission on edit (not just insert) because
 * conflation across in-place updates is a sneaky-bug magnet.
 */
@Dao
interface FavouriteRouteAtStopDao {
    @Query("SELECT * FROM favourite_routes_at_stop ORDER BY position ASC")
    fun observeAll(): Flow<List<FavouriteRouteAtStopEntity>>

    /**
     * Insert or replace by primary key — `@Upsert` so re-favouriting the same
     * `(stopId, routeId, directionId)` refreshes cached display fields (`stopName`,
     * `routeNumber`, …) without forcing the caller to choose between insert vs. update paths.
     */
    @Upsert
    suspend fun upsert(entity: FavouriteRouteAtStopEntity)

    @Query(
        "DELETE FROM favourite_routes_at_stop " +
            "WHERE stopId = :stopId AND routeId = :routeId AND directionId = :directionId",
    )
    suspend fun delete(
        stopId: Int,
        routeId: Int,
        directionId: Int,
    )

    /**
     * Atomically re-numbers `position` to match the order of [orderedIds]. Each triple is
     * `(stopId, routeId, directionId)`; its list index becomes the new `position` value.
     *
     * Wrapped in a single `@Transaction` so concurrent observers of [observeAll] never see an
     * intermediate state where two rows share the same `position` (and the swap can't fail
     * halfway leaving the list partially renumbered). The per-row update query stays trivial.
     */
    @Transaction
    suspend fun reorder(orderedIds: List<Triple<Int, Int, Int>>) {
        orderedIds.forEachIndexed { index, (stopId, routeId, directionId) ->
            updatePosition(
                stopId = stopId,
                routeId = routeId,
                directionId = directionId,
                position = index,
            )
        }
    }

    @Query(
        "UPDATE favourite_routes_at_stop SET position = :position " +
            "WHERE stopId = :stopId AND routeId = :routeId AND directionId = :directionId",
    )
    suspend fun updatePosition(
        stopId: Int,
        routeId: Int,
        directionId: Int,
        position: Int,
    )
}
