package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.NearbyStopsDataSource
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Default impl. Delegates wire-level concerns to [NearbyStopsDataSource] (which knows about
 * Retrofit/OkHttp and resolves the absolute URL itself via `PtvUrlResolver`) and maps
 * domain failures into [Result.Error].
 *
 * Cancellation contract mirrors [StopSearchRepositoryImpl]: [CancellationException] propagates so
 * a stale coroutine doesn't ignore its parent being torn down. Every other throwable becomes
 * `Result.Error` so the ViewModel doesn't need a try/catch around the call.
 *
 * [RouteType.Unknown] is filtered out of the request set here so the data source never sees it —
 * the wire mapping in `:core:network` would otherwise fall back to `Train` (its defensive
 * default), which would silently re-include train stops the caller didn't ask for.
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
            routeTypes: Set<RouteType>,
        ): Result<List<Stop>> =
            try {
                val cleaned = routeTypes - RouteType.Unknown
                Result.Success(dataSource.stopsNear(coordinates, radiusMeters, cleaned))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }
    }
