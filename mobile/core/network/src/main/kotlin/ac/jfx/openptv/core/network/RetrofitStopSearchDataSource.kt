package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.model.toDomain
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Retrofit-backed [StopSearchDataSource]. Lives in `:core:network` because that's the only place
 * `BackendApiService` (also internal) is visible.
 *
 * The absolute URL is composed by injecting [PtvUrlResolver] (production impl reads from
 * `SettingsRepository` in `:core:data`; tests pass a lambda). The resolver picks proxy-mode vs
 * direct-mode signing per call so a Settings-screen edit takes effect on the very next request
 * — no app restart, no Retrofit rebuild.
 *
 * URL-encoding the search term protects against terms containing `/` or `?` reaching the wire
 * untouched and against a misbehaving terminal-comma-on-end.
 */
internal class RetrofitStopSearchDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val urlResolver: PtvUrlResolver,
    ) : StopSearchDataSource {
        override suspend fun searchStops(term: String): List<Stop> {
            val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name())
            val url = urlResolver.resolve("search/$encodedTerm")
            return api.searchStops(url).toDomain()
        }
    }
