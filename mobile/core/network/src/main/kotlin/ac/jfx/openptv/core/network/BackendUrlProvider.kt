package ac.jfx.openptv.core.network

/**
 * Read-only seam exposing the currently-configured backend base URL to the network layer.
 *
 * The interface lives in `:core:network` so internal types like [RetrofitStopSearchDataSource]
 * can inject it without `:core:network` depending on `:core:data` (which would invert the
 * data → network direction). The default implementation lives in `:core:data`, reading from
 * `SettingsRepository` per call so a Settings-screen edit takes effect on the next request
 * without restarting the app or rebuilding the Retrofit graph.
 *
 * Picking `fun interface` lets tests pass a lambda (`BackendUrlProvider { "http://test/" }`)
 * rather than a hand-rolled anonymous class.
 *
 * NIA reference: `nowinandroid/core/network/src/main/kotlin/.../demo/DemoAssetManager.kt` —
 * a `fun interface` in `:core:network` whose impl lives elsewhere (in `:app`).
 */
fun interface BackendUrlProvider {
    suspend fun backendBaseUrl(): String
}
