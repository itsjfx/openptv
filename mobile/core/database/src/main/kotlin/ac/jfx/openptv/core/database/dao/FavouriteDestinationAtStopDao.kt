package ac.jfx.openptv.core.database.dao

import ac.jfx.openptv.core.database.entity.FavouriteDestinationAtStopEntity
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [FavouriteDestinationAtStopEntity]. Ordered by `position` so the UI
 * doesn't need a second sort. Room conflates the `Flow` by default — the DAO test asserts
 * re-emission on edit because conflation across in-place updates is a sneaky-bug magnet.
 */
@Dao
interface FavouriteDestinationAtStopDao {
    @Query("SELECT * FROM favourite_destinations_at_stop ORDER BY position ASC")
    fun observeAll(): Flow<List<FavouriteDestinationAtStopEntity>>

    @Upsert
    suspend fun upsert(entity: FavouriteDestinationAtStopEntity)

    @Query(
        "DELETE FROM favourite_destinations_at_stop " +
            "WHERE stopId = :stopId AND destinationKey = :destinationKey",
    )
    suspend fun delete(
        stopId: Int,
        destinationKey: String,
    )

    /**
     * Atomically re-numbers `position` to match the order of [orderedKeys]. Each pair is
     * `(stopId, destinationKey)`; its list index becomes the new `position` value.
     *
     * Wrapped in a single `@Transaction` so observers never see a partial reorder.
     */
    @Transaction
    suspend fun reorder(orderedKeys: List<Pair<Int, String>>) {
        orderedKeys.forEachIndexed { index, (stopId, destinationKey) ->
            updatePosition(
                stopId = stopId,
                destinationKey = destinationKey,
                position = index,
            )
        }
    }

    @Query(
        "UPDATE favourite_destinations_at_stop SET position = :position " +
            "WHERE stopId = :stopId AND destinationKey = :destinationKey",
    )
    suspend fun updatePosition(
        stopId: Int,
        destinationKey: String,
        position: Int,
    )
}
