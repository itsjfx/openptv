package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.datastore.FollowedTripDataSource
import ac.jfx.openptv.core.model.FollowedTrip
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [FollowedTripRepository]. A thin delegate: the wire format and the actual
 * `DataStore` plumbing live in `:core:datastore`'s [FollowedTripDataSource]; this class exists
 * so consumers depend on a `:core:data` interface (per the architecture's layering — the first
 * datastore-backed repository whose impl lives in `:core:data` rather than `:app`).
 */
@Singleton
internal class FollowedTripRepositoryImpl
    @Inject
    constructor(
        private val dataSource: FollowedTripDataSource,
    ) : FollowedTripRepository {
        override val followedTrip: Flow<FollowedTrip?> = dataSource.followedTrip

        override suspend fun follow(trip: FollowedTrip) {
            dataSource.set(trip)
        }

        override suspend fun unfollow() {
            dataSource.clear()
        }
    }
