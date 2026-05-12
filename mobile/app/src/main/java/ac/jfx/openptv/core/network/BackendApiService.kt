package ac.jfx.openptv.core.network

import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit-bound view of the Go proxy. The base URL is user-configurable (see
 * `SettingsRepository`), so the repository composes the absolute URL per call and hands it to
 * Retrofit via `@Url` rather than relying on the build-time base URL.
 *
 * The Retrofit `Retrofit.Builder().baseUrl(...)` is still required for client construction but
 * its value is a sentinel — every request supplies an absolute URL that overrides it.
 *
 * The PTV search endpoint accepts up to ~3 query parameters (`route_types`, `latitude`,
 * `longitude`) — Phase 02 only needs the simple form. Filters land alongside Nearby (Phase 05).
 */
internal interface BackendApiService {
    @GET
    suspend fun searchStops(@Url url: String): SearchResponseDto
}
