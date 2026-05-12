package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [SettingsRepository]. In-memory backing — no DataStore, no IO.
 *
 * Two construction paths so this works inside the Hilt test graph AND inside plain JUnit
 * unit tests that need to seed initial state:
 *
 * - **Hilt graph** uses the parameterless `@Inject` primary; the seed is the platform default.
 *   Tests inside `@HiltAndroidTest` flows obtain the single `@Singleton` instance via
 *   `@Inject lateinit var` and call [seed] to set the initial state per test.
 * - **Plain unit tests** call the secondary `constructor(initial:)` to get a fresh instance
 *   pre-seeded — the typical shape of ViewModel tests in `:feature:*`.
 */
@Singleton
class FakeSettingsRepository : SettingsRepository {

    private val state = MutableStateFlow(
        AppSettings(
            backendBaseUrl = "http://test.local/api/v3/",
            setupCompleted = true,
        ),
    )
    override val settings: Flow<AppSettings> = state.asStateFlow()

    @Inject
    constructor()

    constructor(initial: AppSettings) : this() {
        state.value = initial
    }

    override suspend fun setBackendBaseUrl(url: String) {
        state.update { it.copy(backendBaseUrl = url) }
    }

    override suspend fun completeSetup(url: String) {
        state.update { it.copy(backendBaseUrl = url, setupCompleted = true) }
    }

    /**
     * Seed the in-memory store for a test that needs a particular initial state. Calling this
     * resets both the URL and the setup flag in a single transaction so a test never observes
     * an inconsistent intermediate state.
     */
    fun seed(settings: AppSettings) {
        state.value = settings
    }
}
