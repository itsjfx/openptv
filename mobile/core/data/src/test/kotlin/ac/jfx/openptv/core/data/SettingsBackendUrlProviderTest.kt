package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the contract that gap #4 buys: every call reads the latest URL from settings, so a
 * Settings-screen edit takes effect on the next request without app restart or Retrofit rebuild.
 */
class SettingsBackendUrlProviderTest {

    @Test
    fun `reads current backendBaseUrl from settings`() = runTest {
        val settings = FakeSettingsRepository().apply {
            seed(AppSettings(backendBaseUrl = "http://first.local/api/v3/", setupCompleted = true))
        }
        val provider = SettingsBackendUrlProvider(settings)

        assertThat(provider.backendBaseUrl()).isEqualTo("http://first.local/api/v3/")
    }

    @Test
    fun `picks up updates between calls`() = runTest {
        val settings = FakeSettingsRepository().apply {
            seed(AppSettings(backendBaseUrl = "http://first.local/api/v3/", setupCompleted = true))
        }
        val provider = SettingsBackendUrlProvider(settings)

        val before = provider.backendBaseUrl()
        settings.setBackendBaseUrl("http://second.local/api/v3/")
        val after = provider.backendBaseUrl()

        assertThat(before).isEqualTo("http://first.local/api/v3/")
        assertThat(after).isEqualTo("http://second.local/api/v3/")
    }
}
