package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.StopSearchDataSource
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Default impl. Delegates wire-level concerns to [StopSearchDataSource] (which knows about
 * Retrofit/OkHttp and resolves the absolute URL itself via `PtvUrlResolver`) and maps
 * domain failures into [Result.Error].
 *
 * Cancellation must propagate (otherwise a stale coroutine ignores its parent being torn down),
 * so [CancellationException] is rethrown rather than swallowed into [Result.Error] — the
 * conventional shape for catch-all blocks in coroutines.
 */
internal class StopSearchRepositoryImpl
    @Inject
    constructor(
        private val dataSource: StopSearchDataSource,
    ) : StopSearchRepository {
        // Repository boundary: any non-cancellation failure (IO, parse, JSON, ...) becomes
        // `Result.Error` so callers don't have to know the underlying type lattice. Catching
        // `Throwable` is the conventional shape; see KDoc above for the cancellation contract.
        @Suppress("TooGenericExceptionCaught")
        override suspend fun searchStops(term: String): Result<List<Stop>> =
            try {
                Result.Success(dataSource.searchStops(term))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }
    }
