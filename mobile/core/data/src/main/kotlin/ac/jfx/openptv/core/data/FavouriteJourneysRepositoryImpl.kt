package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.database.dao.FavouriteJourneyDao
import ac.jfx.openptv.core.model.FavouriteJourney
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import javax.inject.Inject

/**
 * Default impl (issue #209). Maps Room entities into the domain [FavouriteJourney] at the
 * boundary so upstream never sees the entity — same shape as [FavouritesRepositoryImpl].
 *
 * `toggle(...)` reads the current set once (`observeAll().first()`) to decide insert vs delete.
 * The read and the write aren't wrapped in a transaction: a race between two toggles of the
 * *same* pair at worst double-inserts (idempotent under `@Upsert`) or double-deletes (second
 * delete is a no-op) — both settle on a consistent state.
 */
internal class FavouriteJourneysRepositoryImpl
    @Inject
    constructor(
        private val dao: FavouriteJourneyDao,
        private val clock: Clock,
    ) : FavouriteJourneysRepository {
        override fun observe(): Flow<List<FavouriteJourney>> =
            dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        override suspend fun toggle(
            origin: Stop,
            destination: Stop,
        ) {
            val exists =
                dao.observeAll().first().any {
                    it.originStopId == origin.id.value && it.destinationStopId == destination.id.value
                }
            if (exists) {
                dao.delete(
                    originStopId = origin.id.value,
                    destinationStopId = destination.id.value,
                )
            } else {
                dao.upsert(
                    FavouriteJourney(
                        origin = origin,
                        destination = destination,
                        addedAt = clock.now(),
                    ).toEntity(),
                )
            }
        }

        override fun isFavourite(
            originStopId: StopId,
            destinationStopId: StopId,
        ): Flow<Boolean> =
            dao.observeAll()
                .map { rows ->
                    rows.any {
                        it.originStopId == originStopId.value &&
                            it.destinationStopId == destinationStopId.value
                    }
                }
                .distinctUntilChanged()
    }
