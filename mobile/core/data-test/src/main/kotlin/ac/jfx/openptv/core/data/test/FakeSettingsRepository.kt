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
 * One construction path: the parameterless `@Inject` primary, which Hilt uses inside the test
 * graph and which plain JUnit tests can call directly (`FakeSettingsRepository()`). Tests that
 * need a non-default starting state call [seed] right after construction:
 *
 * ```
 * val settings = FakeSettingsRepository().apply {
 *     seed(AppSettings(backendBaseUrl = "...", setupCompleted = true))
 * }
 * ```
 *
 * NIA follows the same one-constructor-plus-`setX`/`seedX` shape on its fakes (e.g.
 * `TestUserDataRepository` in `core/data-test`) — secondary constructors that just delegate to
 * `setX(state)` are a code smell because the seam to mutate state already exists.
 */
@Singleton
class FakeSettingsRepository
    @Inject
    constructor() : SettingsRepository {
        private val state =
            MutableStateFlow(
                AppSettings(
                    backendBaseUrl = DEFAULT_TEST_URL,
                    setupCompleted = true,
                ),
            )
        override val settings: Flow<AppSettings> = state.asStateFlow()

        override val defaultBackendBaseUrl: String = DEFAULT_TEST_URL

        override suspend fun setBackendBaseUrl(url: String) {
            state.update { it.copy(backendBaseUrl = url) }
        }

        override suspend fun setDirectMode(enabled: Boolean) {
            state.update { it.copy(directMode = enabled) }
        }

        override suspend fun setDevId(devId: String) {
            state.update { it.copy(devId = devId) }
        }

        override suspend fun setApiKey(apiKey: String) {
            state.update { it.copy(apiKey = apiKey) }
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

        private companion object {
            // Stable test URL — exposed as both the seeded `backendBaseUrl` and the
            // `defaultBackendBaseUrl` so picker tests can assert "the dialog opens on Default".
            const val DEFAULT_TEST_URL: String = "http://test.local/api/v3/"
        }
    }
