package ac.jfx.openptv.core.network

/**
 * Resolves a PTV-relative path (e.g. `"search/foo"` or
 * `"departures/route_type/0/stop/1071?expand=Run"`) into a fully-qualified absolute URL the
 * data sources can hand to Retrofit's `@Url`.
 *
 * Two production strategies, picked at call-time off the user's settings:
 *
 *  - **Proxy mode** (default): concat `${backendBaseUrl}<path>`. The Go proxy holds the signing
 *    key and signs upstream — the mobile app is unaware.
 *  - **Direct mode**: prepend `/v3/`, append `&devid=<id>&signature=<HMAC-SHA1>`, and prefix
 *    PTV's host. Used when the user has supplied their own dev_id + key.
 *
 * The seam is per-call (not per-app-start) so flipping the toggle in Settings takes effect on
 * the next request without rebuilding Retrofit.
 *
 * Picking `fun interface` lets tests pass a lambda
 * (`PtvUrlResolver { path -> "http://test/api/v3/$path" }`) rather than a hand-rolled
 * anonymous class.
 */
fun interface PtvUrlResolver {
    /**
     * Compose the absolute URL for [path]. Callers pass the part of the URL that comes *after*
     * the proxy's `/api/v3/` prefix — e.g. `"search/flinders"`,
     * `"stops/1071/route_type/0?stop_location=true"`. The path MUST NOT begin with a slash.
     */
    suspend fun resolve(path: String): String
}
