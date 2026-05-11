package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.BackendApiService
import ac.jfx.openptv.core.network.toDomain
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Default impl. Calls the proxy via [BackendApiService] and maps DTOs into domain types.
 *
 * Cancellation must propagate (otherwise a stale coroutine ignores its parent being torn down),
 * so [CancellationException] is rethrown rather than swallowed into [Result.Error] — the
 * conventional shape for catch-all blocks in coroutines.
 */
internal class StopSearchRepositoryImpl @Inject constructor(
    private val api: BackendApiService,
) : StopSearchRepository {
    override suspend fun searchStops(term: String): Result<List<Stop>> = try {
        Result.Success(api.searchStops(term).toDomain())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        Result.Error(t)
    }
}
