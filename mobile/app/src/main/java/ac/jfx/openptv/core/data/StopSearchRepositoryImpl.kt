package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.BackendApiService
import ac.jfx.openptv.core.network.toDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Default impl. Calls the proxy via [BackendApiService] and maps DTOs into domain types.
 *
 * The backend URL is read from [SettingsRepository] per call so a Settings-screen edit takes
 * effect on the very next search without restarting the app or rebuilding the Retrofit graph.
 *
 * Cancellation must propagate (otherwise a stale coroutine ignores its parent being torn down),
 * so [CancellationException] is rethrown rather than swallowed into [Result.Error] — the
 * conventional shape for catch-all blocks in coroutines.
 */
internal class StopSearchRepositoryImpl @Inject constructor(
    private val api: BackendApiService,
    private val settings: SettingsRepository,
) : StopSearchRepository {
    override suspend fun searchStops(term: String): Result<List<Stop>> = try {
        val baseUrl = settings.settings.first().backendBaseUrl
        val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name())
        Result.Success(api.searchStops("${baseUrl}search/$encodedTerm").toDomain())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        Result.Error(t)
    }
}
