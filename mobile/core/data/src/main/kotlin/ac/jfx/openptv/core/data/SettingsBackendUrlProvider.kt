package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.network.BackendUrlProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * `BackendUrlProvider` impl that reads the user's configured URL from [SettingsRepository].
 *
 * Lives in `:core:data` rather than `:core:network` because it depends on `SettingsRepository`,
 * which lives in the data layer. `:core:network` only declares the interface — the same shape
 * NIA uses for `DemoAssetManager` (interface in `:core:network`, impl in `:app`).
 *
 * Reading via `.first()` per call means a Settings edit takes effect on the next search without
 * touching the Retrofit graph. The cost is one StateFlow snapshot per request; negligible for a
 * search-on-keystroke UX.
 */
internal class SettingsBackendUrlProvider @Inject constructor(
    private val settings: SettingsRepository,
) : BackendUrlProvider {
    override suspend fun backendBaseUrl(): String = settings.settings.first().backendBaseUrl
}
