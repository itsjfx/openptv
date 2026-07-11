package ac.jfx.openptv.core.database.dao

import ac.jfx.openptv.core.database.entity.FavouriteJourneyEntity
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for [FavouriteJourneyEntity] (issue #209). Ordered by `addedAt` so the
 * favourites screen renders journeys in the order the user starred them — there's no manual
 * reorder for journey favourites, so insertion order is the stable sort.
 */
@Dao
interface FavouriteJourneyDao {
    @Query("SELECT * FROM favourite_journeys ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<FavouriteJourneyEntity>>

    @Upsert
    suspend fun upsert(entity: FavouriteJourneyEntity)

    @Query(
        "DELETE FROM favourite_journeys " +
            "WHERE originStopId = :originStopId AND destinationStopId = :destinationStopId",
    )
    suspend fun delete(
        originStopId: Int,
        destinationStopId: Int,
    )
}
