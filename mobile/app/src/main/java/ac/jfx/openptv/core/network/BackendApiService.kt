package ac.jfx.openptv.core.network

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Retrofit-bound view of the Go proxy. Path is relative to the `BACKEND_BASE_URL` injected at
 * Retrofit-build time, which already includes `/api/v3/`.
 *
 * The PTV search endpoint accepts up to ~3 query parameters (`route_types`, `latitude`,
 * `longitude`) — Phase 02 only needs the simple form. Filters land alongside Nearby (Phase 05).
 */
internal interface BackendApiService {
    @GET("search/{term}")
    suspend fun searchStops(@Path("term") term: String): SearchResponseDto
}
