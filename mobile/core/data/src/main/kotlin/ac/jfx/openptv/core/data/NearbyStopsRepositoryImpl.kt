package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.NearbyStopsDataSource
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Default impl. Delegates wire-level concerns to [NearbyStopsDataSource] (which knows about
 * Retrofit/OkHttp and resolves the configured base URL itself via `BackendUrlProvider`) and maps
 * domain failures into [Result.Error].
 *
 * Cancellation contract mirrors [StopSearchRepositoryImpl]: [CancellationException] propagates so
 * a stale coroutine doesn't ignore its parent being torn down. Every other throwable becomes
 * `Result.Error` so the ViewModel doesn't need a try/catch around the call.
 */
internal class NearbyStopsRepositoryImpl
    @Inject
    constructor(
        private val dataSource: NearbyStopsDataSource,
    ) : NearbyStopsRepository {
        @Suppress("TooGenericExceptionCaught")
        override suspend fun stopsNear(
            coordinates: Coordinates,
            radiusMeters: Int,
        ): Result<List<Stop>> =
            try {
                Result.Success(dataSource.stopsNear(coordinates, radiusMeters))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }
    }
