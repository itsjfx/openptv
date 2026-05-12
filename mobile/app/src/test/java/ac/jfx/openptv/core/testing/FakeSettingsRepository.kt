package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hand-written fake for [SettingsRepository]. In-memory backing — no DataStore, no IO.
 *
 * Seed with the initial [AppSettings] via the constructor; mutations push new values through
 * the same hot flow consumers see.
 */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(
        backendBaseUrl = "http://test.local/api/v3/",
        setupCompleted = true,
    ),
) : SettingsRepository {

    private val _settings = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = _settings.asStateFlow()

    override suspend fun setBackendBaseUrl(url: String) {
        _settings.update { it.copy(backendBaseUrl = url) }
    }

    override suspend fun completeSetup(url: String) {
        _settings.update { it.copy(backendBaseUrl = url, setupCompleted = true) }
    }
}
