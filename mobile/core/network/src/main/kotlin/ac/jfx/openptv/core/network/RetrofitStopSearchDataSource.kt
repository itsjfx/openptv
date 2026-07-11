package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
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
 *
 * `route_types` is repeated once per requested mode in the query string — PTV's documented
 * convention for "any of these N types", copied from [RetrofitNearbyStopsDataSource]. The
 * filter ordering is sorted-by-wire-code so identical filter sets produce identical URLs
 * (cache-friendly + makes test assertions deterministic). Unlike the nearby endpoint the search
 * path has no other query parameters, so the first entry carries the `?`. The query is composed
 * before [PtvUrlResolver.resolve] so direct-mode signing covers it.
 */
internal class RetrofitStopSearchDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val urlResolver: PtvUrlResolver,
    ) : StopSearchDataSource {
        override suspend fun searchStops(
            term: String,
            routeTypes: Set<RouteType>,
        ): List<Stop> {
            val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name())
            val routeTypeQuery =
                if (routeTypes.isEmpty()) {
                    ""
                } else {
                    routeTypes
                        .map { it.toPtvCode() }
                        .toSortedSet()
                        .joinToString(prefix = "?", separator = "&") { "route_types=$it" }
                }
            val url = urlResolver.resolve("search/$encodedTerm$routeTypeQuery")
            return api.searchStops(url).toDomain()
        }
    }
