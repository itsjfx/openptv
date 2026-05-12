package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Stop
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Retrofit-backed [StopSearchDataSource]. Lives in `:core:network` because that's the only place
 * `BackendApiService` (also internal) is visible.
 *
 * URL-encoding the search term protects against terms containing `/` or `?` reaching the wire
 * untouched and against a misbehaving terminal-comma-on-end.
 */
internal class RetrofitStopSearchDataSource @Inject constructor(
    private val api: BackendApiService,
) : StopSearchDataSource {
    override suspend fun searchStops(baseUrl: String, term: String): List<Stop> {
        val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name())
        return api.searchStops("${baseUrl}search/$encodedTerm").toDomain()
    }
}
